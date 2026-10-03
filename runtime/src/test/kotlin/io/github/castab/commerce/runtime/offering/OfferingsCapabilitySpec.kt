package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.insertTestPrincipal
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.client.JavaHttpClient
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.Jackson
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

@Serializable
data class HostExampleDto(
    val payload: JsonObject,
)

class OfferingsCapabilitySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var runtime: CommerceRuntime
        lateinit var context: CommerceRuntimeContext
        lateinit var http: HttpHandler
        val catalogA = OfferingsCatalogId(UUID.randomUUID())
        val catalogB = OfferingsCatalogId(UUID.randomUUID())
        val catalogC = OfferingsCatalogId(UUID.randomUUID())
        // The application's principals and grants; the runtime only declares what writes require.
        val editor = UserId(UUID.randomUUID())
        val viewer = UserId(UUID.randomUUID())
        val importer = ServiceId(UUID.randomUUID())
        val permissions =
            PermissionResolver { principal ->
                if (principal == editor || principal == importer) setOf(CommercePermissions.OfferingsManage) else emptySet()
            }
        lateinit var editorToken: String

        beforeSpec {
            database = TestDatabase.create()
            val config =
                CommerceRuntimeConfiguration(
                    server = CommerceRuntimeConfiguration.Server(0),
                    database = database.configuration,
                    migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
                )
            runtime =
                commerceRuntime(
                    config,
                    ApplicationContributions(
                        routes = { supplied ->
                            context = supplied
                            val writes =
                                OfferingsHttpAccess.ReadWrite(
                                    AccessControl(
                                        sessionAuthentication(supplied.sessions, BearerSessionToken),
                                        supplied.authorization.permissionCatalog,
                                        permissions,
                                    ),
                                )
                            val a =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(
                                        catalogA,
                                        "/catalog-a",
                                        "catalogA",
                                        writes,
                                        setOf(Tag("Catalog A", "Primary catalog")),
                                    ),
                                )
                            val b =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogB, "/catalog-b", "catalogB", writes, setOf(Tag("Catalog B"))),
                                )
                            val guarded =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogC, "/catalog-c", "catalogC", writes),
                                )
                            val readOnly =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogA, "/catalog-ro", "catalogRo", OfferingsHttpAccess.ReadOnly),
                                )
                            val hostExample = HostExampleDto(JsonObject(mapOf("format" to JsonNull)))
                            val hostExampleBody = jsonBody(HostExampleDto.serializer())
                            val host =
                                contract {
                                    renderer = OpenApi3(ApiInfo("Host API", "1"), Jackson, apiRenderer = offeringsOpenApiRenderer(Jackson))
                                    descriptionPath = "/openapi.json"
                                    routes += a.contractRoutes
                                    routes += b.contractRoutes
                                    routes += guarded.contractRoutes
                                    routes += readOnly.contractRoutes
                                    routes += "/host" meta {
                                        operationId = "hostPing"
                                        returning(Status.OK, hostExampleBody to hostExample)
                                    } bindContract Method.GET to { _: Request -> Response(Status.OK).with(hostExampleBody of hostExample) }
                                }
                            listOf(host)
                        },
                    ),
                ).start()
            context.transactor.insertTestPrincipal(editor)
            context.transactor.insertTestPrincipal(viewer)
            context.transactor.insertTestPrincipal(importer)
            editorToken =
                context.sessions
                    .create(editor)
                    .token.value
            val client = JavaHttpClient()
            http = { request ->
                client(
                    request.uri(
                        request.uri
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(runtime.port()),
                    ),
                )
            }
        }

        afterSpec {
            runtime.close()
            database.close()
        }

        // Requests carry the editor's session unless a test says otherwise.
        fun request(
            method: Method,
            path: String,
            body: String? = null,
            token: String? = editorToken,
            supplyExpectedRevision: Boolean = true,
        ): Response {
            // Existing behavior fixtures observe the current revision. Precondition tests disable this
            // helper and send raw client-supplied revisions, including stale and missing values.
            val catalogId =
                when (path.substringBefore('?').split('/')[1]) {
                    "catalog-a", "catalog-ro" -> catalogA
                    "catalog-b" -> catalogB
                    else -> catalogC
                }
            val mutatesExisting =
                method in setOf(Method.POST, Method.PUT, Method.DELETE) &&
                    path.substringBefore('?').count { it == '/' } > 1
            val revision =
                if (supplyExpectedRevision && mutatesExisting) {
                    context.transactor.inTransaction {
                        context.offeringsSnapshotRepository
                            .retrieveLatestVersion(it, catalogId)
                            ?.revision
                            ?.number ?: 1
                    }
                } else {
                    null
                }
            val target =
                if (method == Method.DELETE && revision != null && !path.contains("expectedRevision=")) {
                    "$path?expectedRevision=$revision"
                } else {
                    path
                }
            val payload =
                if (body != null && method != Method.DELETE && revision != null) {
                    runCatching {
                        val fields = CommerceJson.parse(body).jsonObject
                        if ("expectedRevision" in
                            fields
                        ) {
                            body
                        } else {
                            JsonObject(fields + ("expectedRevision" to JsonPrimitive(revision))).toString()
                        }
                    }.getOrDefault(body)
                } else {
                    body
                }
            val message = Request(method, target).let { if (token == null) it else it.header("Authorization", "Bearer $token") }
            return http(if (payload == null) message else message.header("Content-Type", "application/json").body(payload))
        }

        fun observedRevision(id: OfferingsCatalogId): OfferingsRevision =
            context.transactor.inTransaction {
                context.offeringsSnapshotRepository.retrieveLatestVersion(it, id)?.revision ?: OfferingsRevision.INITIAL
            }

        fun Response.json(): JsonObject = CommerceJson.parse(bodyString()).jsonObject

        // Offering writes take batches. Single-offering behavior is exercised through these
        // helpers, which send one flat offering body as a one-item batch; for update, retire, and
        // restore, the key comes from the last segment of an item path such as `/catalog-b/offerings/x`.
        // Malformed bodies pass through unchanged.
        fun oneOffering(
            body: String,
            key: String? = null,
        ): String {
            val fields = runCatching { CommerceJson.parse(body).jsonObject }.getOrNull() ?: return body
            val item = JsonObject(fields - "expectedRevision" + (key?.let { mapOf("key" to JsonPrimitive(it)) } ?: emptyMap()))
            return JsonObject(fields.filterKeys { it == "expectedRevision" } + ("offerings" to JsonArray(listOf(item)))).toString()
        }

        fun addOffering(
            base: String,
            body: String?,
            token: String? = editorToken,
            supplyExpectedRevision: Boolean = true,
        ): Response = request(Method.POST, "$base/offerings", body?.let { oneOffering(it) }, token, supplyExpectedRevision)

        fun updateOffering(
            path: String,
            body: String,
            token: String? = editorToken,
            supplyExpectedRevision: Boolean = true,
        ): Response =
            request(
                Method.PUT,
                path.substringBeforeLast('/'),
                oneOffering(body, path.substringAfterLast('/')),
                token,
                supplyExpectedRevision,
            )

        fun retireOffering(
            path: String,
            token: String? = editorToken,
        ): Response =
            request(Method.POST, "${path.substringBeforeLast('/')}/retire", """{"keys":["${path.substringAfterLast('/')}"]}""", token)

        fun restoreOffering(
            path: String,
            body: String,
            token: String? = editorToken,
        ): Response =
            request(Method.POST, "${path.substringBeforeLast('/')}/restore", oneOffering(body, path.substringAfterLast('/')), token)

        /** The single offering of a one-item batch response. */
        fun Response.offering(): JsonObject = json()["offerings"]!!.jsonArray.single().jsonObject

        test("bindings reject ambiguous paths, operation ID prefixes, and blank OpenAPI tags") {
            listOf("", "catalog", "/catalog/", "/catalog?x=1", "/catalog/{id}", "/catalog//nested").forEach { path ->
                shouldThrow<IllegalArgumentException> {
                    OfferingsHttpBinding(catalogA, path, "catalogA", OfferingsHttpAccess.ReadOnly)
                }
            }
            listOf("", "with space", "9prefix", "a-b").forEach { prefix ->
                shouldThrow<IllegalArgumentException> {
                    OfferingsHttpBinding(catalogA, "/catalog", prefix, OfferingsHttpAccess.ReadOnly)
                }
            }
            listOf("", " ").forEach { name ->
                shouldThrow<IllegalArgumentException> {
                    OfferingsHttpBinding(catalogA, "/catalog", "catalogA", OfferingsHttpAccess.ReadOnly, setOf(Tag(name)))
                }
            }
        }

        test("writes require an authenticated principal that currently holds the offerings management permission") {
            val category = """{"key":"sizes","displayName":"Sizes"}"""
            val viewerToken =
                context.sessions
                    .create(viewer)
                    .token.value

            // No principal: 401, whatever the request body, and nothing is written.
            listOf(
                Triple("/catalog-c", null, null),
                Triple("/catalog-c/categories", category, null),
                Triple("/catalog-c/offerings", "{", null),
                Triple("/catalog-c", null, "not-a-token"),
            ).forEach { (path, body, token) ->
                request(Method.POST, path, body, token).let {
                    it.status shouldBe Status.UNAUTHORIZED
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "unauthenticated"
                }
            }
            // An authenticated principal without the permission: 403, and nothing is written.
            listOf("/catalog-c" to null, "/catalog-c/categories" to category, "/catalog-c/offerings" to "{").forEach { (path, body) ->
                request(Method.POST, path, body, viewerToken).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "forbidden"
                }
            }
            request(Method.GET, "/catalog-c", token = null).status shouldBe Status.NOT_FOUND

            // Principals holding the permission reach the handlers, humans and services alike.
            request(
                Method.POST,
                "/catalog-c",
                token =
                    context.sessions
                        .create(importer)
                        .token.value,
            ).status shouldBe Status.CREATED
            request(Method.POST, "/catalog-c/categories", category).status shouldBe Status.CREATED
            request(Method.GET, "/catalog-c", token = null).json()["revision"]!!.jsonPrimitive.content shouldBe "2"
        }

        test("reads and read-only bindings keep their exposure and need no principal") {
            request(Method.POST, "/catalog-ro").status shouldBe Status.NOT_FOUND
            val viewerToken =
                context.sessions
                    .create(viewer)
                    .token.value
            listOf(null, viewerToken, "not-a-token").forEach { token ->
                request(Method.GET, "/catalog-c", token = token).status shouldBe Status.OK
                request(Method.GET, "/catalog-c/categories", token = token).status shouldBe Status.OK
                // Earlier revisions are not retained, so there is no exact-revision read.
                request(Method.GET, "/catalog-c/revisions/1", token = token).status shouldBe Status.NOT_FOUND
                // Read-only bindings have no write routes to authorize.
                request(Method.POST, "/catalog-ro", token = token).status shouldBe Status.NOT_FOUND
            }

            val paths = request(Method.GET, "/openapi.json", token = null).json()["paths"]!!.jsonObject
            listOf("/catalog-c" to "post", "/catalog-c/categories" to "post", "/catalog-c/offerings" to "post").forEach { (path, method) ->
                val responses = paths[path]!!.jsonObject[method]!!.jsonObject["responses"]!!.jsonObject
                responses.keys.containsAll(setOf("401", "403")) shouldBe true
            }
            listOf("/catalog-c", "/catalog-c/categories", "/catalog-ro", "/catalog-ro/offerings").forEach { path ->
                val responses = paths[path]!!.jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject
                (responses.keys intersect setOf("401", "403")) shouldBe emptySet()
            }
        }

        test("catalog operations advance one current revision") {
            val create = CreateOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)
            val addCategory = AddOfferingCategory(context.transactor, context.offeringsSnapshotRepository)
            val addOfferings = AddOfferings(context.transactor, context.offeringsSnapshotRepository)
            val latest = GetOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)
            val id = OfferingsCatalogId(UUID.randomUUID())
            shouldThrow<CommerceFailure.NotFound> { latest(id) }
            create(id).revision.number shouldBe 1
            shouldThrow<CommerceFailure.Conflict> { create(id) }
            shouldThrow<CommerceFailure.NotFound> {
                addOfferings(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            OfferingKey("missing"),
                            OfferingCategoryKey("absent"),
                            "Missing",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            addCategory(id, observedRevision(id), OfferingCategory(OfferingCategoryKey("a"), "A")).reference.revision.number shouldBe 2
            addCategory(id, observedRevision(id), OfferingCategory(OfferingCategoryKey("b"), "B")).reference.revision.number shouldBe 3
            shouldThrow<CommerceFailure.Conflict> {
                addCategory(
                    id,
                    observedRevision(id),
                    OfferingCategory(OfferingCategoryKey("a"), "Again"),
                )
            }
            val prices =
                listOf(
                    null,
                    OfferingPrice.Fixed(Money(BigDecimal("120.00"), Currency.getInstance("USD"))),
                    OfferingPrice.PerQuantity(Money(BigDecimal("0.75"), Currency.getInstance("USD")), QuantityDimension("guest")),
                    OfferingPrice.PerDuration(Money(BigDecimal("50.00"), Currency.getInstance("USD")), Duration.ofNanos(123456789)),
                )
            // One batch adds all four in order, in one successor revision.
            addOfferings(
                id,
                observedRevision(id),
                prices.mapIndexed {
                    index,
                    price,
                    ->
                    Offering(
                        OfferingKey("item$index"),
                        OfferingCategoryKey("a"),
                        "Item $index",
                        price = price,
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    )
                },
            ).reference.revision.number shouldBe 4
            latest(id).categories.map { it.key.value }.shouldContainExactly("a", "b")
            latest(id).offerings.map { it.key.value }.shouldContainExactly("item0", "item1", "item2", "item3")
            latest(id).offerings.map { it.price }.shouldContainExactly(prices)
            latest(id).revision.number shouldBe 4
            shouldThrow<CommerceFailure.Conflict> {
                addOfferings(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            OfferingKey("item0"),
                            OfferingCategoryKey("a"),
                            "Again",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            val staleA = latest(id)
            val staleB = latest(id)
            val nextA = staleA.revise(staleA.categories + OfferingCategory(OfferingCategoryKey("c"), "C"), staleA.offerings)
            val nextB = staleB.revise(staleB.categories + OfferingCategory(OfferingCategoryKey("d"), "D"), staleB.offerings)
            context.transactor.inTransaction { context.offeringsSnapshotRepository.save(it, nextA) }
            shouldThrow<CommerceFailure.Conflict> {
                context.transactor.inTransaction { context.offeringsSnapshotRepository.save(it, nextB) }
            }
            latest(id).categories.map { it.key.value }.shouldContainExactly("a", "b", "c")
        }

        test("HTTP routes, read-only exposure, and host OpenAPI compose") {
            request(Method.POST, "/catalog-a").status shouldBe Status.CREATED
            request(Method.POST, "/catalog-b").status shouldBe Status.CREATED
            request(Method.POST, "/catalog-a/categories", """{"key":"flavors","displayName":"Flavors"}""").status shouldBe Status.CREATED
            addOffering(
                "/catalog-a",
                """{"key":"vanilla","selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"flavors","displayName":"Vanilla","price":null}""",
            ).status shouldBe
                Status.CREATED
            request(Method.GET, "/catalog-a").json()["revision"]!!.jsonPrimitive.content shouldBe "3"
            request(Method.GET, "/catalog-a").json()["previousRevision"]!!.jsonPrimitive.content shouldBe "2"
            request(Method.GET, "/catalog-a/revisions/2").status shouldBe Status.NOT_FOUND
            request(Method.GET, "/catalog-b").json()["categories"]!!.jsonArray.size shouldBe 0
            request(Method.GET, "/catalog-a/categories").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/categories/flavors").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/categories/flavors/offerings").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/offerings").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/offerings/vanilla").status shouldBe Status.OK
            request(Method.GET, "/catalog-ro", token = null).status shouldBe Status.OK
            request(Method.POST, "/catalog-ro").status shouldBe Status.NOT_FOUND
            request(Method.POST, "/catalog-ro/categories").status shouldBe Status.NOT_FOUND
            addOffering("/catalog-ro", null).status shouldBe Status.NOT_FOUND
            request(Method.GET, "/catalog-a/offerings/missing").json()["code"]!!.jsonPrimitive.content shouldBe "not_found"
            val malformed = addOffering("/catalog-a", "{")
            malformed.json()["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
            val fixed =
                Json.encodeToString(
                    OfferingDto.serializer(),
                    OfferingDto(
                        "fixed",
                        "flavors",
                        "Fixed",
                        selectionState = OfferingSelectionStateDto.ENABLED,
                        availability = OfferingAvailabilityDto.AVAILABLE,
                        price = OfferingPriceDto("FIXED", "120.00", "USD"),
                    ),
                )
            val quantity =
                Json.encodeToString(
                    OfferingDto.serializer(),
                    OfferingDto(
                        "quantity",
                        "flavors",
                        "Quantity",
                        selectionState = OfferingSelectionStateDto.ENABLED,
                        availability = OfferingAvailabilityDto.AVAILABLE,
                        price = OfferingPriceDto("PER_QUANTITY", "0.75", "USD", "guest"),
                    ),
                )
            val duration =
                Json.encodeToString(
                    OfferingDto.serializer(),
                    OfferingDto(
                        "duration",
                        "flavors",
                        "Duration",
                        selectionState = OfferingSelectionStateDto.ENABLED,
                        availability = OfferingAvailabilityDto.AVAILABLE,
                        price = OfferingPriceDto("PER_DURATION", "50.00", "USD", interval = "PT0.123456789S"),
                    ),
                )
            // One request adds all three, in order, in one successor revision.
            val before =
                request(Method.GET, "/catalog-a")
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt()
            request(Method.POST, "/catalog-a/offerings", """{"offerings":[$fixed,$quantity,$duration]}""").let {
                it.status shouldBe Status.CREATED
                it
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt() shouldBe before + 1
                it.json()["offerings"]!!.jsonArray.map { item -> item.jsonObject["key"]!!.jsonPrimitive.content } shouldBe
                    listOf("fixed", "quantity", "duration")
            }
            val prices = request(Method.GET, "/catalog-a/offerings").json()["offerings"]!!.jsonArray
            prices[1]
                .jsonObject["price"]!!
                .jsonObject["amount"]!!
                .jsonPrimitive.content shouldBe "120.00"
            prices[2]
                .jsonObject["price"]!!
                .jsonObject["dimension"]!!
                .jsonPrimitive.content shouldBe "guest"
            prices[3]
                .jsonObject["price"]!!
                .jsonObject["interval"]!!
                .jsonPrimitive.content shouldBe "PT0.123456789S"
            val invalid =
                listOf(
                    fixed.replace("USD", "INVALID"),
                    fixed.replace("120.00", "not-a-decimal"),
                    duration.replace("PT0.123456789S", "PT0S"),
                    duration.replace("PT0.123456789S", "-PT1H"),
                    duration.replace("PT0.123456789S", "nonsense"),
                    quantity.replace("\"dimension\":\"guest\"", "\"interval\":\"PT1H\""),
                    fixed.replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"dimension\":\"guest\""),
                    fixed.replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"interval\":\"PT1H\""),
                    quantity.replace(",\"dimension\":\"guest\"", ""),
                    quantity.replace("\"dimension\":\"guest\"", "\"dimension\":\"guest\",\"interval\":\"PT1H\""),
                    duration.replace(",\"interval\":\"PT0.123456789S\"", ""),
                    duration.replace("\"interval\":\"PT0.123456789S\"", "\"dimension\":\"guest\",\"interval\":\"PT0.123456789S\""),
                )
            invalid.forEach { body ->
                addOffering(
                    "/catalog-a",
                    body
                        .replace(
                            "\"key\":\"fixed\"",
                            "\"key\":\"invalid\"",
                        ).replace("\"key\":\"duration\"", "\"key\":\"invalid\"")
                        .replace("\"key\":\"quantity\"", "\"key\":\"invalid\""),
                ).status shouldBe
                    Status.UNPROCESSABLE_ENTITY
            }
            val additive =
                addOffering(
                    "/catalog-a",
                    """{"key":"fixed-with-extra","selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"flavors","displayName":"Fixed with Extra",
                      "price":{"kind":"FIXED","amount":"120.00","currency":"USD","futureField":"ignored"}}""",
                )
            additive.status shouldBe Status.CREATED
            additive
                .offering()
                .jsonObject["price"]!!
                .jsonObject.keys shouldBe setOf("kind", "amount", "currency")
            request(Method.GET, "/catalog-a/offerings/fixed-with-extra")
                .json()["offering"]!!
                .jsonObject["price"]!!
                .jsonObject.keys shouldBe setOf("kind", "amount", "currency")
            val conflicting =
                addOffering(
                    "/catalog-a",
                    """{"key":"fixed-with-dimension","selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"flavors","displayName":"Fixed with Dimension",
                      "price":{"kind":"FIXED","amount":"120.00","currency":"USD","dimension":"guest"}}""",
                )
            conflicting.status shouldBe Status.UNPROCESSABLE_ENTITY
            conflicting.json()["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
            priceShapes.forEachIndexed { index, (price, status) ->
                val body = """{"key":"shape$index","selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"flavors","displayName":"Shape $index","price":$price}"""
                addOffering("/catalog-a", body).status shouldBe status
            }
            val openapi = request(Method.GET, "/openapi.json").json()
            val paths = openapi["paths"]!!.jsonObject
            paths.containsKey("/host") shouldBe true
            paths.containsKey("/catalog-a") shouldBe true
            paths.containsKey("/catalog-b") shouldBe true
            paths.keys.none { it.contains("/revisions") } shouldBe true
            listOf(
                "/catalog-a/categories",
                "/catalog-a/categories/{categoryKey}",
                "/catalog-a/categories/{categoryKey}/offerings",
                "/catalog-a/offerings",
                "/catalog-a/offerings/{offeringKey}",
            ).forEach { paths.containsKey(it) shouldBe true }
            paths["/catalog-a"]!!.jsonObject.containsKey("post") shouldBe true
            paths["/catalog-ro"]!!.jsonObject.containsKey("post") shouldBe false
            paths["/catalog-ro/categories"]!!.jsonObject.containsKey("post") shouldBe false
            paths["/catalog-ro/offerings"]!!.jsonObject.containsKey("post") shouldBe false
            val addOfferingContract = paths["/catalog-a/offerings"]!!.jsonObject["post"]!!.jsonObject
            addOfferingContract["requestBody"]!!.jsonObject.containsKey("content") shouldBe true
            listOf("201", "401", "403", "400", "422", "404", "409").forEach { code ->
                addOfferingContract["responses"]!!.jsonObject.containsKey(code) shouldBe true
            }
            addOfferingContract["responses"]!!
                .jsonObject["422"]!!
                .jsonObject["content"]!!
                .jsonObject["application/json"]!!
                .jsonObject["example"]!!
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe "validation_failed"
            paths["/catalog-a"]!!
                .jsonObject["get"]!!
                .jsonObject["operationId"]!!
                .jsonPrimitive.content shouldBe "catalogAGetCatalog"
            paths["/catalog-b"]!!
                .jsonObject["get"]!!
                .jsonObject["operationId"]!!
                .jsonPrimitive.content shouldBe "catalogBGetCatalog"

            // Each binding's host-supplied tags group all of its operations; untagged bindings keep http4k's default.
            fun operationTags(prefix: String) =
                paths
                    .filterKeys { it == prefix || it.startsWith("$prefix/") }
                    .values
                    .flatMap { it.jsonObject.values }
                    .map { operation -> operation.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content } }
            operationTags("/catalog-a").size shouldBe 17
            operationTags("/catalog-a").forEach { it shouldContainExactly listOf("Catalog A") }
            operationTags("/catalog-b").size shouldBe 17
            operationTags("/catalog-b").forEach { it shouldContainExactly listOf("Catalog B") }
            (operationTags("/catalog-c") + operationTags("/catalog-ro")).forEach { tags ->
                tags.none { it == "Catalog A" || it == "Catalog B" } shouldBe true
            }
            val documentTags = openapi["tags"]!!.jsonArray.map { it.jsonObject }
            documentTags
                .single { it["name"]!!.jsonPrimitive.content == "Catalog A" }["description"]!!
                .jsonPrimitive.content shouldBe "Primary catalog"
            documentTags.count { it["name"]!!.jsonPrimitive.content == "Catalog B" } shouldBe 1
        }

        test("OpenAPI price union and every request and response reference match runtime validation") {
            val document = request(Method.GET, "/openapi.json").json()

            fun assertNoNullSchemaFormats(
                element: JsonElement,
                path: String = "root",
            ) {
                when (element) {
                    is JsonObject -> {
                        check(element["format"] != JsonNull) { "format: null at $path" }
                        element.forEach { (key, value) ->
                            if (key !in setOf("example", "examples", "default", "const", "enum")) {
                                assertNoNullSchemaFormats(value, "$path.$key")
                            }
                        }
                    }
                    is JsonArray -> element.forEachIndexed { index, value -> assertNoNullSchemaFormats(value, "$path[$index]") }
                    else -> Unit
                }
            }
            assertNoNullSchemaFormats(document)
            val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
            schemas["ErrorResponse"]!!.jsonObject["properties"]!!.jsonObject.containsKey("violations") shouldBe false
            schemas["ValidationErrorResponse"]!!.jsonObject["properties"]!!.jsonObject.containsKey("violations") shouldBe true
            val errorRequired =
                schemas["ValidationErrorResponse"]!!
                    .jsonObject["required"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            errorRequired.contains("violations") shouldBe false
            val price = schemas["OfferingPriceDto"]!!.jsonObject
            val refs = price["oneOf"]!!.jsonArray.map { it.jsonObject["\$ref"]!!.jsonPrimitive.content }
            refs.shouldContainExactly(
                "#/components/schemas/FixedOfferingPrice",
                "#/components/schemas/PerQuantityOfferingPrice",
                "#/components/schemas/PerDurationOfferingPrice",
            )
            val discriminator = price["discriminator"]!!.jsonObject
            discriminator["propertyName"]!!.jsonPrimitive.content shouldBe "kind"
            val mappings = discriminator["mapping"]!!.jsonObject
            listOf("FIXED", "PER_QUANTITY", "PER_DURATION").forEachIndexed { index, kind ->
                mappings[kind]!!.jsonPrimitive.content shouldBe refs[index]
            }

            data class Branch(
                val name: String,
                val kind: String,
                val fields: Set<String>,
                val forbidden: Set<String>,
            )
            listOf(
                Branch("FixedOfferingPrice", "FIXED", setOf("kind", "amount", "currency"), setOf("dimension", "interval")),
                Branch("PerQuantityOfferingPrice", "PER_QUANTITY", setOf("kind", "amount", "currency", "dimension"), setOf("interval")),
                Branch("PerDurationOfferingPrice", "PER_DURATION", setOf("kind", "amount", "currency", "interval"), setOf("dimension")),
            ).forEach { (name, kind, fields, forbidden) ->
                val branch = schemas[name]!!.jsonObject
                branch["type"]!!.jsonPrimitive.content shouldBe "object"
                branch.containsKey("additionalProperties") shouldBe false
                branch["properties"]!!.jsonObject.keys shouldBe fields
                branch["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet() shouldBe fields
                branch["properties"]!!
                    .jsonObject["kind"]!!
                    .jsonObject["enum"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content shouldBe kind
                val example = branch["example"]!!.jsonObject
                example.keys shouldBe fields
                branch.accepts(example, schemas) shouldBe true
                branch.accepts(JsonObject(example + ("futureField" to JsonPrimitive("ignored"))), schemas) shouldBe true
                forbidden.forEach { field ->
                    branch.accepts(JsonObject(example + (field to JsonPrimitive("x"))), schemas) shouldBe false
                }
                fields.forEach { field -> branch.accepts(JsonObject(example - field), schemas) shouldBe false }
            }
            priceShapes.forEach { (body, status) ->
                price.accepts(CommerceJson.parse(body), schemas) shouldBe (status == Status.CREATED)
            }

            fun reachesPrice(
                element: JsonElement,
                visited: Set<String> = emptySet(),
            ): Boolean =
                when (element) {
                    is JsonObject -> {
                        val ref = element["\$ref"]?.jsonPrimitive?.content
                        if (ref == "#/components/schemas/OfferingPriceDto") {
                            true
                        } else if (ref != null && ref !in visited) {
                            reachesPrice(schemas[ref.substringAfterLast('/')]!!, visited + ref)
                        } else {
                            element.values.any { reachesPrice(it, visited) }
                        }
                    }
                    is JsonArray -> element.any { reachesPrice(it, visited) }
                    else -> false
                }

            val paths = document["paths"]!!.jsonObject

            fun responseSchema(
                path: String,
                method: String,
                status: String,
            ): JsonElement =
                paths[path]!!
                    .jsonObject[method]!!
                    .jsonObject["responses"]!!
                    .jsonObject[status]!!
                    .jsonObject["content"]!!
                    .jsonObject["application/json"]!!
                    .jsonObject["schema"]!!

            val create = paths["/catalog-a/offerings"]!!.jsonObject["post"]!!.jsonObject
            val createRequest = create["requestBody"]!!.jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!
            reachesPrice(createRequest) shouldBe true
            listOf(
                "/catalog-a/offerings" to "put",
                "/catalog-a/offerings/restore" to "post",
            ).forEach { (path, method) ->
                val schema =
                    paths[path]!!
                        .jsonObject[method]!!
                        .jsonObject["requestBody"]!!
                        .jsonObject["content"]!!
                        .jsonObject["application/json"]!!
                        .jsonObject["schema"]!!
                reachesPrice(schema) shouldBe true
            }
            listOf(
                Triple("/catalog-a/offerings", "post", "201"),
                Triple("/catalog-a/offerings/{offeringKey}", "get", "200"),
                Triple("/catalog-a/offerings", "get", "200"),
                Triple("/catalog-a/offerings", "put", "200"),
                Triple("/catalog-a/offerings/restore", "post", "200"),
                Triple("/catalog-a/retired/offerings", "get", "200"),
                Triple("/catalog-a/categories/{categoryKey}/offerings", "get", "200"),
                Triple("/catalog-a", "get", "200"),
                Triple("/catalog-a", "post", "201"),
            ).forEach { (path, method, status) ->
                reachesPrice(responseSchema(path, method, status)) shouldBe true
            }

            fun responseExample(
                path: String,
                method: String,
                status: String,
            ): JsonObject =
                paths[path]!!
                    .jsonObject[method]!!
                    .jsonObject["responses"]!!
                    .jsonObject[status]!!
                    .jsonObject["content"]!!
                    .jsonObject["application/json"]!!
                    .jsonObject["example"]!!
                    .jsonObject

            responseExample("/host", "get", "200")["payload"]!!.jsonObject["format"] shouldBe JsonNull

            val initialized = responseExample("/catalog-a", "post", "201")
            initialized["catalogId"]!!.jsonPrimitive.content shouldBe catalogA.value.toString()
            initialized["revision"]!!.jsonPrimitive.content shouldBe "1"
            (initialized["previousRevision"] ?: JsonNull) shouldBe JsonNull
            initialized["categories"]!!.jsonArray.size shouldBe 0
            listOf(
                Triple("/catalog-a/categories", "post", "201") to 2,
                Triple("/catalog-a/offerings", "post", "201") to 3,
                Triple("/catalog-a", "get", "200") to 3,
                Triple("/catalog-a/categories", "get", "200") to 3,
                Triple("/catalog-a/categories/{categoryKey}", "get", "200") to 3,
                Triple("/catalog-a/categories/{categoryKey}/offerings", "get", "200") to 3,
                Triple("/catalog-a/offerings", "get", "200") to 3,
                Triple("/catalog-a/offerings/{offeringKey}", "get", "200") to 3,
            ).forEach { (route, revision) ->
                val (path, method, status) = route
                responseExample(path, method, status)["revision"]!!.jsonPrimitive.content shouldBe revision.toString()
            }
            responseExample("/catalog-a", "get", "200")["previousRevision"]!!.jsonPrimitive.content shouldBe "2"
            // The empty initialization example must not erase the shared catalog definition's shape.
            val catalogSchema = schemas["OfferingsCatalogDto"]!!.jsonObject["properties"]!!.jsonObject
            catalogSchema.keys shouldBe setOf("catalogId", "revision", "previousRevision", "categories")
            catalogSchema["categories"]!!
                .jsonObject["items"]!!
                .jsonObject["\$ref"]!!
                .jsonPrimitive.content shouldBe "#/components/schemas/CatalogCategoryDto"

            var checkedPriceExamples = 0

            fun checkExamples(
                element: JsonElement,
                path: String = "",
            ) {
                when (element) {
                    is JsonObject -> {
                        if (element["kind"] is JsonPrimitive &&
                            element["amount"] is JsonPrimitive &&
                            element["currency"] is JsonPrimitive
                        ) {
                            try {
                                OfferingPriceDto(
                                    element["kind"]!!.jsonPrimitive.content,
                                    element["amount"]!!.jsonPrimitive.content,
                                    element["currency"]!!.jsonPrimitive.content,
                                    element["dimension"]?.jsonPrimitive?.content,
                                    element["interval"]?.jsonPrimitive?.content,
                                ).toDomain()
                            } catch (failure: CommerceFailure.ValidationFailed) {
                                error("Invalid OpenAPI price example at $path: $element")
                            }
                            checkedPriceExamples++
                        }
                        element.forEach { (key, value) -> checkExamples(value, "$path/$key") }
                    }
                    is JsonArray -> element.forEachIndexed { index, value -> checkExamples(value, "$path/$index") }
                    else -> Unit
                }
            }
            checkExamples(document)
            (checkedPriceExamples > 0) shouldBe true
        }

        test("every lifecycle mutation is protected, absent from read-only bindings, and documented by OpenAPI") {
            val viewerToken =
                context.sessions
                    .create(viewer)
                    .token.value
            val paths = request(Method.GET, "/openapi.json").json()["paths"]!!.jsonObject
            val schemas = request(Method.GET, "/openapi.json").json()["components"]!!.jsonObject["schemas"]!!.jsonObject
            schemas["OfferingCategoryMutationDto"]!!.jsonObject["properties"]!!.jsonObject.containsKey("key") shouldBe false
            // Offering batches carry each key in the item; the per-key offering mutation routes are gone.
            schemas["OfferingsBatchDto"]!!.jsonObject["properties"]!!.jsonObject.keys shouldBe setOf("expectedRevision", "offerings")
            listOf("put", "delete").forEach { paths["/catalog-a/offerings/{offeringKey}"]!!.jsonObject.containsKey(it) shouldBe false }
            paths.containsKey("/catalog-a/offerings/{offeringKey}/restore") shouldBe false
            listOf(
                Triple("offerings", Method.PUT, "UpdateOfferings"),
                Triple("offerings/retire", Method.POST, "RetireOfferings"),
                Triple("offerings/restore", Method.POST, "RestoreOfferings"),
                Triple("categories/{categoryKey}", Method.PUT, "UpdateCategory"),
                Triple("categories/{categoryKey}", Method.DELETE, "RetireCategory"),
                Triple("categories/{categoryKey}/restore", Method.POST, "RestoreCategory"),
            ).forEach { (suffix, method, operation) ->
                val concrete = suffix.replace("{offeringKey}", "missing").replace("{categoryKey}", "missing")
                request(method, "/catalog-c/$concrete", "{", token = null).status shouldBe Status.UNAUTHORIZED
                request(method, "/catalog-c/$concrete", "{", token = viewerToken).status shouldBe Status.FORBIDDEN
                request(method, "/catalog-ro/$concrete", "{").status shouldBe Status.NOT_FOUND
                val contract = paths["/catalog-a/$suffix"]!!.jsonObject[method.name.lowercase()]!!.jsonObject
                contract["operationId"]!!.jsonPrimitive.content shouldBe "catalogA$operation"
                contract["responses"]!!.jsonObject.keys shouldBe
                    setOf("200", "401", "403", "400", "404", "409", "422")
                (paths["/catalog-ro/$suffix"]?.jsonObject?.containsKey(method.name.lowercase()) ?: false) shouldBe false
                if (method != Method.DELETE) {
                    request(method, "/catalog-b/$concrete", "{").status shouldBe Status.BAD_REQUEST
                    val invalid =
                        if (suffix == "offerings/retire") {
                            """{"keys":["has space"]}"""
                        } else if (suffix.startsWith("offerings")) {
                            """{"offerings":[{"key":"missing","selectionState":"ENABLED","availability":"AVAILABLE",
                                "category":"lifecycle","displayName":" "}]}"""
                        } else {
                            """{"displayName":" ","minimumSelections":-1}"""
                        }
                    request(method, "/catalog-b/$concrete", invalid).status shouldBe Status.UNPROCESSABLE_ENTITY
                    val body =
                        if (suffix == "offerings/retire") {
                            """{"keys":["missing"]}"""
                        } else if (suffix.startsWith("offerings")) {
                            """{"offerings":[{"key":"missing","selectionState":"ENABLED","availability":"AVAILABLE",
                                "category":"lifecycle","displayName":"Unknown"}]}"""
                        } else {
                            """{"displayName":"Unknown"}"""
                        }
                    request(method, "/catalog-b/$concrete", body).status shouldBe Status.NOT_FOUND
                } else {
                    request(method, "/catalog-b/$concrete").status shouldBe Status.NOT_FOUND
                }
                if (suffix.startsWith("categories")) {
                    request(method, "/catalog-b/${concrete.replace("missing", "%20")}", """{"displayName":"Category"}""").status shouldBe
                        Status.UNPROCESSABLE_ENTITY
                }
            }
            listOf("offerings", "categories").forEach { kind ->
                request(Method.GET, "/catalog-ro/retired/$kind", token = null).status shouldBe Status.NOT_FOUND
                paths["/catalog-a/retired/$kind"]!!
                    .jsonObject["get"]!!
                    .jsonObject["operationId"]!!
                    .jsonPrimitive.content shouldBe
                    "catalogAListRetired${kind.replaceFirstChar(Char::uppercase)}"
            }
        }

        test("HTTP lifecycle exposes last retired representations and successor revisions") {
            val base = "/catalog-b"
            val category = """{"key":"lifecycle","displayName":"Flavors"}"""
            request(Method.POST, "$base/categories", category).status shouldBe Status.CREATED

            fun body(amount: String) =
                """{"selectionState":"ENABLED","availability":"AVAILABLE","category":"lifecycle","displayName":"Horchata",
                    "price":{"kind":"PER_QUANTITY","amount":"$amount","currency":"USD","dimension":"guest"}}"""
            val addBody = body("0.50").replaceFirst("{", """{"key":"horchata", """)
            addOffering("$base", addBody).status shouldBe Status.CREATED
            val added = request(Method.GET, base).json()
            val path = "$base/offerings/horchata"
            restoreOffering(path, body("0.75")).status shouldBe Status.CONFLICT
            addOffering("$base", addBody).status shouldBe Status.CONFLICT
            updateOffering(path, body("0.75")).let {
                it.status shouldBe Status.OK
                it.offering()["key"]!!.jsonPrimitive.content shouldBe "horchata"
            }
            val updated = request(Method.GET, base).json()
            request(Method.DELETE, "$base/categories/lifecycle").status shouldBe Status.CONFLICT
            updateOffering(path, body("0.75").replace("lifecycle", "missing-category")).status shouldBe Status.NOT_FOUND
            retireOffering(path).let {
                it.status shouldBe Status.OK
                it
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt() shouldBe updated["revision"]!!.jsonPrimitive.content.toInt() + 1
            }
            val retired = request(Method.GET, base).json()
            request(Method.GET, path).status shouldBe Status.NOT_FOUND
            addOffering("$base", addBody).status shouldBe Status.CONFLICT
            updateOffering(path, body("0.75")).status shouldBe Status.CONFLICT
            retireOffering(path).status shouldBe Status.CONFLICT
            request(Method.GET, "$base/retired/offerings").json().let { response ->
                response["revision"] shouldBe retired["revision"]
                val entry = response["offerings"]!!.jsonArray.single().jsonObject
                entry["lastSeenRevision"] shouldBe updated["revision"]
                entry["offering"]!!
                    .jsonObject["price"]!!
                    .jsonObject["amount"]!!
                    .jsonPrimitive.content shouldBe "0.75"
            }
            restoreOffering(path, body("1.00")).status shouldBe Status.OK
            val restored = request(Method.GET, base).json()
            request(Method.GET, "$base/retired/offerings").json()["offerings"]!!.jsonArray shouldBe JsonArray(emptyList())
            listOf(added, updated, retired, restored)
                .map { it["revision"]!!.jsonPrimitive.content.toInt() }
                .zipWithNext()
                .forEach { (earlier, later) -> later shouldBe earlier + 1 }

            retireOffering(path).status shouldBe Status.OK
            val categoryPath = "$base/categories/lifecycle"
            val editCategory = """{"displayName":"New Flavors","description":"Changed","minimumSelections":1,"maximumSelections":2}"""
            request(Method.PUT, categoryPath, editCategory).status shouldBe Status.OK
            val updatedCategory = request(Method.GET, base).json()
            request(Method.DELETE, categoryPath).status shouldBe Status.OK
            val retiredCategory = request(Method.GET, base).json()
            request(Method.POST, "$base/categories", category).status shouldBe Status.CONFLICT
            request(Method.PUT, categoryPath, editCategory).status shouldBe Status.CONFLICT
            request(Method.DELETE, categoryPath).status shouldBe Status.CONFLICT
            restoreOffering(path, body("1.00")).status shouldBe Status.NOT_FOUND
            request(Method.GET, "$base/retired/categories").json().let { response ->
                response["revision"] shouldBe retiredCategory["revision"]
                val entry = response["categories"]!!.jsonArray.single().jsonObject
                entry["lastSeenRevision"] shouldBe updatedCategory["revision"]
                entry["category"]!!.jsonObject["displayName"]!!.jsonPrimitive.content shouldBe "New Flavors"
            }
            request(Method.POST, "$categoryPath/restore", editCategory).status shouldBe Status.OK
            request(Method.POST, "$categoryPath/restore", editCategory).status shouldBe Status.CONFLICT
            restoreOffering(path, body("1.00")).status shouldBe Status.OK
            request(Method.GET, "$base/retired/categories").json()["categories"]!!.jsonArray shouldBe JsonArray(emptyList())
            request(Method.POST, "$base/categories", """{"key":"retired","displayName":"Retired is a legal key"}""").status shouldBe
                Status.CREATED
            request(Method.GET, "$base/categories/retired").status shouldBe Status.OK
            addOffering(
                "$base",
                """{"key":"retired","selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"retired","displayName":"Legal key"}""",
            ).status shouldBe
                Status.CREATED
            request(Method.GET, "$base/offerings/retired").status shouldBe Status.OK
        }

        test("retired discovery belongs only to the live permission-protected management surface") {
            val viewerToken =
                context.sessions
                    .create(viewer)
                    .token.value
            val paths = request(Method.GET, "/openapi.json").json()["paths"]!!.jsonObject
            listOf("offerings", "categories").forEach { kind ->
                val path = "/catalog-b/retired/$kind"
                request(Method.GET, path, token = null).status shouldBe Status.UNAUTHORIZED
                request(Method.GET, path, token = "not-a-token").status shouldBe Status.UNAUTHORIZED
                request(Method.GET, path, token = viewerToken).status shouldBe Status.FORBIDDEN
                request(Method.GET, path).status shouldBe Status.OK
                listOf(null, viewerToken, editorToken).forEach { token ->
                    request(Method.GET, "/catalog-ro/retired/$kind", token = token).status shouldBe Status.NOT_FOUND
                    request(Method.GET, "/catalog-ro/$kind", token = token).status shouldBe Status.OK
                }
                paths.containsKey("/catalog-ro/retired/$kind") shouldBe false
                paths[path]!!
                    .jsonObject["get"]!!
                    .jsonObject["responses"]!!
                    .jsonObject.keys shouldBe setOf("200", "401", "403", "404")
            }
            // Ordinary reads retain the host's read policy.
            request(Method.GET, "/catalog-ro", token = null).status shouldBe Status.OK
        }

        test("expected revisions are required, typed, and distinguish malformed, invalid, and stale requests") {
            val schemas = request(Method.GET, "/openapi.json").json()["components"]!!.jsonObject["schemas"]!!.jsonObject
            listOf("OfferingsBatchDto", "RetireOfferingsDto", "OfferingCategoryMutationDto", "AddOfferingCategoryDto").forEach { name ->
                val schema = schemas[name]!!.jsonObject
                schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("expectedRevision") shouldBe true
                schema["properties"]!!
                    .jsonObject["expectedRevision"]!!
                    .jsonObject["type"]!!
                    .jsonPrimitive.content shouldBe "integer"
            }
            listOf(
                "offerings" to Method.PUT,
                "offerings/restore" to Method.POST,
                "offerings/retire" to Method.POST,
                "offerings" to Method.POST,
                "categories/new" to Method.PUT,
                "categories/new/restore" to Method.POST,
                "categories" to Method.POST,
            ).forEach { (suffix, method) ->
                val baseBody =
                    if (suffix == "offerings/retire") {
                        """{"keys":["new"]}"""
                    } else if (suffix.startsWith("offerings")) {
                        """{"offerings":[{"key":"new","selectionState":"ENABLED","availability":"AVAILABLE","category":"lifecycle",
                            "displayName":"New"}]}"""
                    } else {
                        """{"key":"new","displayName":"New"}"""
                    }
                val path = "/catalog-b/$suffix"

                fun raw(body: String): Response = request(method, path, body, supplyExpectedRevision = false)
                raw(baseBody).let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
                }
                listOf("\"abc\"", "1.5", "null").forEach { syntax ->
                    raw(baseBody.replaceFirst("{", "{\"expectedRevision\":$syntax,")).status shouldBe Status.BAD_REQUEST
                }
                listOf(0, -1).forEach { invalid ->
                    raw(baseBody.replaceFirst("{", "{\"expectedRevision\":$invalid,")).let {
                        it.status shouldBe Status.UNPROCESSABLE_ENTITY
                        it.json()["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
                    }
                }
                raw(baseBody.replaceFirst("{", "{\"expectedRevision\":1,")).let {
                    it.status shouldBe Status.CONFLICT
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "conflict"
                }
            }
            val paths = request(Method.GET, "/openapi.json").json()["paths"]!!.jsonObject
            listOf("categories" to "categoryKey").forEach { (kind, key) ->
                val template = "/catalog-b/$kind/{$key}"
                val parameter =
                    paths[template]!!
                        .jsonObject["delete"]!!
                        .jsonObject["parameters"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it["name"]!!.jsonPrimitive.content == "expectedRevision" }
                parameter["in"]!!.jsonPrimitive.content shouldBe "query"
                parameter["required"]!!.jsonPrimitive.content shouldBe "true"
                parameter["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "integer"
                val path = "/catalog-b/$kind/new"

                fun raw(suffix: String): Response = request(Method.DELETE, path + suffix, supplyExpectedRevision = false)
                raw("").status shouldBe Status.BAD_REQUEST
                raw("?expectedRevision=1&expectedRevision=2").status shouldBe Status.BAD_REQUEST
                listOf("abc", "1.5", "2147483648").forEach { syntax ->
                    raw("?expectedRevision=$syntax").let {
                        it.status shouldBe Status.BAD_REQUEST
                        it.json()["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
                    }
                }
                listOf(0, -1).forEach { invalid ->
                    raw("?expectedRevision=$invalid").status shouldBe Status.UNPROCESSABLE_ENTITY
                }
                raw("?expectedRevision=1").let {
                    it.status shouldBe Status.CONFLICT
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "conflict"
                }
                // Authentication runs before conditional-query extraction, as it does for mutation bodies.
                request(Method.DELETE, path, token = null, supplyExpectedRevision = false).status shouldBe Status.UNAUTHORIZED
            }
        }

        test("HTTP rejects a stale full replacement without overwriting price or advancing the revision") {
            val base = "/catalog-b"
            request(Method.POST, "$base/categories", """{"key":"stale-guard","displayName":"Guard"}""").status shouldBe Status.CREATED
            val body = """{"selectionState":"ENABLED","availability":"AVAILABLE",
                    "category":"stale-guard","displayName":"Horchata","price":{"kind":"FIXED","amount":"0.50","currency":"USD"}}"""
            addOffering("$base", body.replaceFirst("{", """{"key":"stale-horchata", """)).status shouldBe Status.CREATED
            val observed = request(Method.GET, base).json()
            val path = "$base/offerings/stale-horchata"
            updateOffering(path, body.replace("0.50", "0.75")).status shouldBe Status.OK
            val committed = request(Method.GET, base).json()
            val stale =
                body
                    .replaceFirst("{", "{\"expectedRevision\":${observed["revision"]!!.jsonPrimitive.content},")
                    .replace("Horchata", "Changed display name")
            updateOffering(path, stale, supplyExpectedRevision = false).let {
                it.status shouldBe Status.CONFLICT
                it.json()["code"]!!.jsonPrimitive.content shouldBe "conflict"
            }
            request(Method.GET, base).json() shouldBe committed
            request(Method.GET, path)
                .json()["offering"]!!
                .jsonObject["price"]!!
                .jsonObject["amount"]!!
                .jsonPrimitive.content shouldBe
                "0.75"
            // The caller can reload and explicitly acknowledge the new revision.
            updateOffering(
                path,
                body.replaceFirst("{", "{\"expectedRevision\":${committed["revision"]!!.jsonPrimitive.content},"),
                supplyExpectedRevision = false,
            ).status shouldBe Status.OK
        }

        test("HTTP requires both enums and accepts all four independent combinations on add update and restore") {
            val base = "/catalog-b"
            request(Method.POST, "$base/categories", """{"key":"eligibility","displayName":"Eligibility"}""").status shouldBe Status.CREATED
            val schemas = request(Method.GET, "/openapi.json").json()["components"]!!.jsonObject["schemas"]!!.jsonObject
            listOf("OfferingDto").forEach { name ->
                val schema = schemas[name]!!.jsonObject
                schema["required"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .containsAll(listOf("selectionState", "availability")) shouldBe true
                schema.containsKey("not") shouldBe false
                val properties = schema["properties"]!!.jsonObject
                listOf("selectionState" to listOf("ENABLED", "DISABLED"), "availability" to listOf("AVAILABLE", "UNAVAILABLE"))
                    .forEach { (field, values) ->
                        val property = properties[field]!!.jsonObject
                        property["type"]!!.jsonPrimitive.content shouldBe "string"
                        property["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.shouldContainExactly(values)
                        property.containsKey("nullable") shouldBe false
                        listOf(JsonNull, JsonPrimitive("UNKNOWN")).forEach { property.accepts(it, schemas) shouldBe false }
                        values.forEach { property.accepts(JsonPrimitive(it), schemas) shouldBe true }
                    }
            }
            val combinations =
                listOf(
                    "ENABLED" to "AVAILABLE",
                    "ENABLED" to "UNAVAILABLE",
                    "DISABLED" to "AVAILABLE",
                    "DISABLED" to "UNAVAILABLE",
                )
            combinations.forEachIndexed { index, (selectionState, availability) ->
                val fields =
                    JsonObject(
                        mapOf(
                            "key" to JsonPrimitive("eligibility-$index"),
                            "category" to JsonPrimitive("eligibility"),
                            "displayName" to JsonPrimitive("Item"),
                            "selectionState" to JsonPrimitive(selectionState),
                            "availability" to JsonPrimitive(availability),
                        ),
                    )
                val path = "$base/offerings/eligibility-$index"
                listOf<Pair<String, (String) -> Response>>(
                    "add" to { body -> addOffering(base, body) },
                    "update" to { body -> updateOffering(path, body) },
                    "restore" to { body -> restoreOffering(path, body) },
                ).forEach { (action, write) ->
                    if (action == "restore") {
                        val lastSeen = request(Method.GET, base).json()["revision"]
                        retireOffering(path).status shouldBe Status.OK
                        request(Method.GET, "$base/retired/offerings")
                            .json()["offerings"]!!
                            .jsonArray
                            .map { it.jsonObject }
                            .single { it["offering"]!!.jsonObject["key"] == fields["key"] }
                            .let { retired ->
                                retired["lastSeenRevision"] shouldBe lastSeen
                                retired["offering"]!!.jsonObject["selectionState"] shouldBe fields["selectionState"]
                                retired["offering"]!!.jsonObject["availability"] shouldBe fields["availability"]
                            }
                    }
                    val before = request(Method.GET, base).json()
                    listOf("selectionState", "availability").forEach { field ->
                        listOf(
                            JsonObject(fields - field),
                            JsonObject(fields + (field to JsonNull)),
                            JsonObject(fields + (field to JsonPrimitive("UNKNOWN"))),
                        ).forEach { malformed ->
                            write(malformed.toString()).let {
                                it.status shouldBe Status.BAD_REQUEST
                                it.json()["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
                            }
                        }
                    }
                    request(Method.GET, base).json() shouldBe before
                    write(fields.toString()).status shouldBe if (action == "add") Status.CREATED else Status.OK
                    val current = request(Method.GET, base).json()
                    current["revision"]!!.jsonPrimitive.content.toInt() shouldBe before["revision"]!!.jsonPrimitive.content.toInt() + 1
                    val read = request(Method.GET, path).json()["offering"]!!.jsonObject
                    read["selectionState"] shouldBe fields["selectionState"]
                    read["availability"] shouldBe fields["availability"]
                    listOf("$base/offerings", "$base/categories/eligibility/offerings").forEach { endpoint ->
                        request(Method.GET, endpoint)
                            .json()["offerings"]!!
                            .jsonArray
                            .map { it.jsonObject }
                            .single { it["key"] == fields["key"] } shouldBe read
                    }
                    current["categories"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it["key"] == JsonPrimitive("eligibility") }["offerings"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it["key"] == fields["key"] } shouldBe read
                }
            }
        }

        test("HTTP offering writes take batches and apply each batch atomically in one revision") {
            val base = "/catalog-b"
            request(Method.POST, "$base/categories", """{"key":"batch","displayName":"Batch"}""").status shouldBe Status.CREATED

            fun item(
                key: String,
                name: String = key,
                category: String = "batch",
            ) = """{"key":"$key","category":"$category","displayName":"$name","selectionState":"ENABLED","availability":"AVAILABLE"}"""

            fun batch(vararg items: String) = """{"offerings":[${items.joinToString(",")}]}"""

            fun revision() =
                request(Method.GET, base)
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt()

            fun keys() =
                request(Method.GET, "$base/categories/batch/offerings")
                    .json()["offerings"]!!
                    .jsonArray
                    .map { it.jsonObject["key"]!!.jsonPrimitive.content }

            val start = revision()
            request(Method.POST, "$base/offerings", batch(item("b1"), item("b2"), item("b3"))).let {
                it.status shouldBe Status.CREATED
                it
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt() shouldBe start + 1
            }
            keys() shouldBe listOf("b1", "b2", "b3")
            request(Method.PUT, "$base/offerings", batch(item("b3", "Third"), item("b1", "First"))).let {
                it.status shouldBe Status.OK
                it.json()["offerings"]!!.jsonArray.map { o -> o.jsonObject["displayName"]!!.jsonPrimitive.content } shouldBe
                    listOf("Third", "First")
            }
            keys() shouldBe listOf("b1", "b2", "b3")
            request(Method.POST, "$base/offerings/retire", """{"keys":["b1","b3"]}""").let {
                it.status shouldBe Status.OK
                it
                    .json()["revision"]!!
                    .jsonPrimitive.content
                    .toInt() shouldBe start + 3
            }
            keys() shouldBe listOf("b2")
            request(Method.POST, "$base/offerings/restore", batch(item("b3"), item("b1"))).status shouldBe Status.OK
            keys() shouldBe listOf("b2", "b3", "b1")
            revision() shouldBe start + 4
            // The item-path read still serves an offering whose key is "retire" or "restore".
            request(Method.GET, "$base/offerings/retire").status shouldBe Status.NOT_FOUND

            // One bad item rejects the whole batch: no offering and no revision is written.
            val before = request(Method.GET, base).json()
            listOf(
                Triple(Method.POST, "$base/offerings", batch(item("fresh"), item("b2"))) to Status.CONFLICT,
                Triple(Method.POST, "$base/offerings", batch(item("fresh"), item("other", category = "absent"))) to Status.NOT_FOUND,
                Triple(Method.POST, "$base/offerings", batch(item("fresh"), item("blank", " "))) to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.PUT, "$base/offerings", batch(item("b2", "Changed"), item("missing"))) to Status.NOT_FOUND,
                Triple(Method.POST, "$base/offerings/retire", """{"keys":["b2","missing"]}""") to Status.NOT_FOUND,
                Triple(Method.POST, "$base/offerings/restore", batch(item("b2"))) to Status.CONFLICT,
                // A batch names at least one offering, each once, by a valid key.
                Triple(Method.POST, "$base/offerings", batch()) to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.PUT, "$base/offerings", batch(item("b2"), item("b2"))) to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.POST, "$base/offerings/retire", """{"keys":[]}""") to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.POST, "$base/offerings/retire", """{"keys":["b2","b2"]}""") to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.POST, "$base/offerings/retire", """{"keys":["has space"]}""") to Status.UNPROCESSABLE_ENTITY,
                Triple(Method.POST, "$base/offerings/retire", """{"keys":"b2"}""") to Status.BAD_REQUEST,
                Triple(Method.PUT, "$base/offerings", """{"offerings":{}}""") to Status.BAD_REQUEST,
            ).forEach { (call, status) ->
                val (method, path, body) = call
                withClue("$method $path $body") { request(method, path, body).status shouldBe status }
            }
            request(Method.GET, base).json() shouldBe before
            request(Method.GET, "$base/offerings/fresh").status shouldBe Status.NOT_FOUND
        }

        test("HTTP carries badge, status note, and info note as optional text replaced with the rest of the offering") {
            val base = "/catalog-b"
            request(Method.POST, "$base/categories", """{"key":"notes","displayName":"Notes"}""").status shouldBe Status.CREATED
            val schemas = request(Method.GET, "/openapi.json").json()["components"]!!.jsonObject["schemas"]!!.jsonObject
            listOf("OfferingDto").forEach { name ->
                val schema = schemas[name]!!.jsonObject
                val properties = schema["properties"]!!.jsonObject
                listOf("badge", "statusNote", "infoNote").forEach { field ->
                    withClue("$name.$field") {
                        // Declared exactly like the optional description text.
                        properties[field]!!.jsonObject.minus("example") shouldBe
                            properties["description"]!!.jsonObject.minus("example")
                        schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(field) shouldBe false
                    }
                }
            }
            val path = "$base/offerings/peanut-butter"
            val noted =
                """{"category":"notes","displayName":"Peanut Butter","description":"Creamy",
                    "selectionState":"ENABLED","availability":"UNAVAILABLE","badge":"Popular","statusNote":"Back this fall",
                    "infoNote":"Contains peanuts"}"""
            addOffering("$base", noted.replaceFirst("{", """{"key":"peanut-butter",""")).status shouldBe
                Status.CREATED
            request(Method.GET, path).json()["offering"]!!.jsonObject.let { offering ->
                offering["badge"]!!.jsonPrimitive.content shouldBe "Popular"
                offering["statusNote"]!!.jsonPrimitive.content shouldBe "Back this fall"
                offering["infoNote"]!!.jsonPrimitive.content shouldBe "Contains peanuts"
                offering["availability"]!!.jsonPrimitive.content shouldBe "UNAVAILABLE"
            }
            listOf("badge", "statusNote", "infoNote").forEach { field ->
                val before = request(Method.GET, base).json()
                updateOffering(path, noted.replace(Regex(""""$field":"[^"]*""""), """"$field":" """")).let {
                    it.status shouldBe Status.UNPROCESSABLE_ENTITY
                    it.json()["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
                }
                request(Method.GET, base).json() shouldBe before
            }
            // An update is a full replacement: omitted optional text is absent afterwards, like description.
            val plain = """{"category":"notes","displayName":"Peanut Butter","selectionState":"ENABLED","availability":"AVAILABLE"}"""
            updateOffering(path, plain).status shouldBe Status.OK
            request(Method.GET, path).json()["offering"]!!.jsonObject.let { offering ->
                offering.containsKey("badge") shouldBe false
                offering.containsKey("statusNote") shouldBe false
                offering.containsKey("infoNote") shouldBe false
                offering.containsKey("description") shouldBe false
            }
            updateOffering(path, noted).status shouldBe Status.OK
            retireOffering(path).status shouldBe Status.OK
            request(Method.GET, "$base/retired/offerings")
                .json()["offerings"]!!
                .jsonArray
                .map { it.jsonObject["offering"]!!.jsonObject }
                .single { it["key"]!!.jsonPrimitive.content == "peanut-butter" }["badge"]!!
                .jsonPrimitive.content shouldBe "Popular"
            restoreOffering(path, plain).status shouldBe Status.OK
            request(Method.GET, path).json()["offering"]!!.jsonObject.containsKey("badge") shouldBe false
        }

        test("OpenAPI selection enums remain strict when an offering example has no price") {
            val body = jsonBody(OfferingDto.serializer())
            val sample =
                OfferingDto(
                    "item",
                    "choice",
                    "Item",
                    selectionState = OfferingSelectionStateDto.DISABLED,
                    availability = OfferingAvailabilityDto.AVAILABLE,
                )
            val host =
                contract {
                    renderer = OpenApi3(ApiInfo("Unpriced catalog", "1"), Jackson, apiRenderer = offeringsOpenApiRenderer(Jackson))
                    descriptionPath = "/openapi.json"
                    routes += "/item" meta {
                        operationId = "unpricedItem"
                        returning(Status.OK, body to sample)
                    } bindContract Method.GET to { _: Request -> Response(Status.OK).with(body of sample) }
                }
            val schema =
                host(Request(Method.GET, "/openapi.json"))
                    .json()["components"]!!
                    .jsonObject["schemas"]!!
                    .jsonObject["OfferingDto"]!!
                    .jsonObject
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("selectionState", "availability")) shouldBe
                true
            schema["properties"]!!
                .jsonObject["selectionState"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
                .shouldContainExactly("ENABLED", "DISABLED")
            schema["properties"]!!
                .jsonObject["availability"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
                .shouldContainExactly("AVAILABLE", "UNAVAILABLE")
            schema.containsKey("not") shouldBe false
            val actual = host(Request(Method.GET, "/item")).json()
            actual.containsKey("price") shouldBe false
            actual.containsKey("description") shouldBe false
            actual.containsKey("badge") shouldBe false
            actual.containsKey("statusNote") shouldBe false
            actual.containsKey("infoNote") shouldBe false
            actual["selectionState"]!!.jsonPrimitive.content shouldBe "DISABLED"
            actual["availability"]!!.jsonPrimitive.content shouldBe "AVAILABLE"
        }

        test("path parameter failures match each route's documented error statuses") {
            val paths = request(Method.GET, "/openapi.json").json()["paths"]!!.jsonObject
            mapOf(
                "/catalog-a/categories/{categoryKey}" to
                    listOf(
                        "/catalog-a/categories/%20" to "validation_failed",
                        "/catalog-a/categories/a%20b" to "validation_failed",
                        "/catalog-a/categories/missing" to "not_found",
                    ),
                "/catalog-a/categories/{categoryKey}/offerings" to
                    listOf(
                        "/catalog-a/categories/%20/offerings" to "validation_failed",
                        "/catalog-a/categories/missing/offerings" to "not_found",
                    ),
                "/catalog-a/offerings/{offeringKey}" to
                    listOf(
                        "/catalog-a/offerings/%20" to "validation_failed",
                        "/catalog-a/offerings/a%20b" to "validation_failed",
                        "/catalog-a/offerings/missing" to "not_found",
                    ),
            ).forEach { (template, cases) ->
                val observed =
                    cases.map { (path, code) ->
                        val response = request(Method.GET, path)
                        response.json()["code"]!!.jsonPrimitive.content shouldBe code
                        response.status.code.toString()
                    }
                val documented =
                    paths[template]!!
                        .jsonObject["get"]!!
                        .jsonObject["responses"]!!
                        .jsonObject.keys
                        .filterNot { it.startsWith("2") }
                documented.shouldContainExactlyInAnyOrder(observed.distinct())
            }
        }
    })

/** Price request shapes and the status the HTTP capability answers; the OpenAPI schema must agree. */
private val priceShapes =
    listOf(
        """{"kind":"FIXED","amount":"120.00","currency":"USD"}""" to Status.CREATED,
        """{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest"}""" to Status.CREATED,
        """{"kind":"PER_DURATION","amount":"50.00","currency":"USD","interval":"PT1H"}""" to Status.CREATED,
        """{"kind":"FIXED","amount":"120.00","currency":"USD","futureField":"ignored"}""" to Status.CREATED,
        """{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest","futureField":1}""" to Status.CREATED,
        """{"kind":"FIXED","amount":"120.00","currency":"USD","dimension":"guest"}""" to Status.UNPROCESSABLE_ENTITY,
        """{"kind":"FIXED","amount":"120.00","currency":"USD","interval":"PT1H"}""" to Status.UNPROCESSABLE_ENTITY,
        """{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD"}""" to Status.UNPROCESSABLE_ENTITY,
        """{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest","interval":"PT1H"}""" to
            Status.UNPROCESSABLE_ENTITY,
        """{"kind":"PER_DURATION","amount":"50.00","currency":"USD"}""" to Status.UNPROCESSABLE_ENTITY,
        """{"kind":"PER_DURATION","amount":"50.00","currency":"USD","dimension":"guest","interval":"PT1H"}""" to
            Status.UNPROCESSABLE_ENTITY,
        """{"kind":"OTHER","amount":"1.00","currency":"USD"}""" to Status.UNPROCESSABLE_ENTITY,
        """{"kind":"FIXED","currency":"USD"}""" to Status.BAD_REQUEST,
    )

/**
 * Evaluates the JSON Schema keywords the price schemas use. An unsupported keyword fails
 * the test, so a schema change can never pass by being silently ignored.
 */
private fun JsonObject.accepts(
    value: JsonElement,
    schemas: JsonObject,
): Boolean {
    val supported = setOf("\$ref", "type", "properties", "required", "enum", "not", "anyOf", "oneOf", "example", "discriminator")
    check((keys - supported).isEmpty()) { "Unsupported schema keywords ${keys - supported}" }
    val fields = value as? JsonObject
    val checks =
        listOf(
            { this["\$ref"]?.let { schemas[it.jsonPrimitive.content.substringAfterLast('/')]!!.jsonObject.accepts(value, schemas) } },
            {
                this["type"]?.let {
                    when (val type = it.jsonPrimitive.content) {
                        "object" -> fields != null
                        "string" -> value is JsonPrimitive && value.isString
                        else -> error("Unsupported schema type $type")
                    }
                }
            },
            { this["enum"]?.let { value in it.jsonArray } },
            { this["required"]?.let { required -> fields == null || required.jsonArray.all { it.jsonPrimitive.content in fields } } },
            {
                this["properties"]?.let { properties ->
                    fields == null ||
                        properties.jsonObject.all { (name, schema) -> fields[name]?.let { schema.jsonObject.accepts(it, schemas) } ?: true }
                }
            },
            { this["not"]?.let { !it.jsonObject.accepts(value, schemas) } },
            { this["anyOf"]?.let { branches -> branches.jsonArray.any { it.jsonObject.accepts(value, schemas) } } },
            { this["oneOf"]?.let { branches -> branches.jsonArray.count { it.jsonObject.accepts(value, schemas) } == 1 } },
        )
    return checks.all { check -> check() ?: true }
}
