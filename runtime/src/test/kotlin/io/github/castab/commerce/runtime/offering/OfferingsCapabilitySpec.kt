package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
import org.http4k.format.Jackson
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

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
                                    AccessControl(sessionAuthentication(supplied.sessions, BearerSessionToken), permissions),
                                )
                            val a =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogA, "/catalog-a", "catalogA", writes),
                                )
                            val b =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogB, "/catalog-b", "catalogB", writes),
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
                                        returning(Status.OK)
                                    } bindContract Method.GET to { _: Request -> Response(Status.OK) }
                                }
                            listOf(host)
                        },
                    ),
                ).start()
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
        ): Response {
            val message = Request(method, path).let { if (token == null) it else it.header("Authorization", "Bearer $token") }
            return http(if (body == null) message else message.header("Content-Type", "application/json").body(body))
        }

        fun Response.json(): JsonObject = CommerceJson.parse(bodyString()).jsonObject

        test("bindings reject ambiguous paths and operation ID prefixes") {
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
                request(Method.GET, "/catalog-c/revisions/1", token = token).status shouldBe Status.OK
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

        test("READ_ONLY remains a deprecated alias of ReadOnly, and there is no READ_WRITE without AccessControl") {
            @Suppress("DEPRECATION")
            val legacy = OfferingsHttpBinding(catalogA, "/legacy", "legacy", OfferingsHttpAccess.READ_ONLY)
            legacy.access shouldBe OfferingsHttpAccess.ReadOnly

            val companion = OfferingsHttpAccess.Companion::class.memberProperties.associateBy { it.name }
            companion.keys shouldBe setOf("READ_ONLY")
            companion
                .getValue("READ_ONLY")
                .findAnnotation<Deprecated>()!!
                .replaceWith.expression shouldBe
                "OfferingsHttpAccess.ReadOnly"
        }

        test("catalog operations append revisions and preserve exact historical reads") {
            val create = CreateOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)
            val addCategory = AddOfferingCategory(context.transactor, context.offeringsSnapshotRepository)
            val addOffering = AddOffering(context.transactor, context.offeringsSnapshotRepository)
            val latest = GetOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)
            val revision = GetOfferingsCatalogRevision(context.transactor, context.offeringsSnapshotRepository)
            val id = OfferingsCatalogId(UUID.randomUUID())
            shouldThrow<CommerceFailure.NotFound> { latest(id) }
            create(id).revision.number shouldBe 1
            shouldThrow<CommerceFailure.Conflict> { create(id) }
            shouldThrow<CommerceFailure.NotFound> {
                addOffering(
                    id,
                    Offering(OfferingKey("missing"), OfferingCategoryKey("absent"), "Missing"),
                )
            }
            addCategory(id, OfferingCategory(OfferingCategoryKey("a"), "A")).reference.revision.number shouldBe 2
            addCategory(id, OfferingCategory(OfferingCategoryKey("b"), "B")).reference.revision.number shouldBe 3
            shouldThrow<CommerceFailure.Conflict> { addCategory(id, OfferingCategory(OfferingCategoryKey("a"), "Again")) }
            val prices =
                listOf(
                    null,
                    OfferingPrice.Fixed(Money(BigDecimal("120.00"), Currency.getInstance("USD"))),
                    OfferingPrice.PerQuantity(Money(BigDecimal("0.75"), Currency.getInstance("USD")), QuantityDimension("guest")),
                    OfferingPrice.PerDuration(Money(BigDecimal("50.00"), Currency.getInstance("USD")), Duration.ofNanos(123456789)),
                )
            prices.forEachIndexed { index, price ->
                addOffering(id, Offering(OfferingKey("item$index"), OfferingCategoryKey("a"), "Item $index", price = price))
            }
            latest(id).categories.map { it.key.value }.shouldContainExactly("a", "b")
            latest(id).offerings.map { it.key.value }.shouldContainExactly("item0", "item1", "item2", "item3")
            latest(id).offerings.map { it.price }.shouldContainExactly(prices)
            revision(OfferingsSnapshotReference(id, OfferingsRevision.INITIAL)).categories.size shouldBe 0
            revision(OfferingsSnapshotReference(id, OfferingsRevision.of(3))).offerings.size shouldBe 0
            shouldThrow<CommerceFailure.Conflict> { addOffering(id, Offering(OfferingKey("item0"), OfferingCategoryKey("a"), "Again")) }
            shouldThrow<CommerceFailure.NotFound> { revision(OfferingsSnapshotReference(id, OfferingsRevision.of(999))) }
            val staleA = latest(id)
            val staleB = latest(id)
            val nextA = staleA.revise(staleA.categories + OfferingCategory(OfferingCategoryKey("c"), "C"), staleA.offerings)
            val nextB = staleB.revise(staleB.categories + OfferingCategory(OfferingCategoryKey("d"), "D"), staleB.offerings)
            context.transactor.inTransaction { context.offeringsSnapshotRepository.insert(it, nextA) }
            shouldThrow<CommerceFailure.Conflict> {
                context.transactor.inTransaction { context.offeringsSnapshotRepository.insert(it, nextB) }
            }
            latest(id).categories.map { it.key.value }.shouldContainExactly("a", "b", "c")
        }

        test("HTTP routes, historical revisions, read-only exposure, and host OpenAPI compose") {
            request(Method.POST, "/catalog-a").status shouldBe Status.CREATED
            request(Method.POST, "/catalog-b").status shouldBe Status.CREATED
            request(Method.POST, "/catalog-a/categories", """{"key":"flavors","displayName":"Flavors"}""").status shouldBe Status.CREATED
            request(
                Method.POST,
                "/catalog-a/offerings",
                """{"key":"vanilla","category":"flavors","displayName":"Vanilla","price":null}""",
            ).status shouldBe
                Status.CREATED
            request(Method.GET, "/catalog-a").json()["revision"]!!.jsonPrimitive.content shouldBe "3"
            request(Method.GET, "/catalog-a/revisions/1").json()["categories"]!!.jsonArray.size shouldBe 0
            request(
                Method.GET,
                "/catalog-a/revisions/2",
            ).json()["categories"]!!.jsonArray.first().jsonObject["offerings"]!!.jsonArray.size shouldBe
                0
            request(
                Method.GET,
                "/catalog-a/revisions/3",
            ).json()["categories"]!!.jsonArray.first().jsonObject["offerings"]!!.jsonArray.size shouldBe
                1
            request(Method.GET, "/catalog-b").json()["categories"]!!.jsonArray.size shouldBe 0
            request(Method.GET, "/catalog-a/categories").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/categories/flavors").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/categories/flavors/offerings").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/offerings").status shouldBe Status.OK
            request(Method.GET, "/catalog-a/offerings/vanilla").status shouldBe Status.OK
            request(Method.GET, "/catalog-ro", token = null).status shouldBe Status.OK
            request(Method.POST, "/catalog-ro").status shouldBe Status.NOT_FOUND
            request(Method.POST, "/catalog-ro/categories").status shouldBe Status.NOT_FOUND
            request(Method.POST, "/catalog-ro/offerings").status shouldBe Status.NOT_FOUND
            request(Method.GET, "/catalog-a/offerings/missing").json()["code"]!!.jsonPrimitive.content shouldBe "not_found"
            request(Method.GET, "/catalog-a/revisions/0").json()["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
            val malformed = request(Method.POST, "/catalog-a/offerings", "{")
            malformed.json()["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
            val fixed =
                Json.encodeToString(
                    OfferingDto.serializer(),
                    OfferingDto("fixed", "flavors", "Fixed", price = OfferingPriceDto("FIXED", "120.00", "USD")),
                )
            val quantity =
                Json.encodeToString(
                    OfferingDto.serializer(),
                    OfferingDto(
                        "quantity",
                        "flavors",
                        "Quantity",
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
                        price = OfferingPriceDto("PER_DURATION", "50.00", "USD", interval = "PT0.123456789S"),
                    ),
                )
            listOf(fixed, quantity, duration).forEach { request(Method.POST, "/catalog-a/offerings", it).status shouldBe Status.CREATED }
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
                request(
                    Method.POST,
                    "/catalog-a/offerings",
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
                request(
                    Method.POST,
                    "/catalog-a/offerings",
                    """{"key":"fixed-with-extra","category":"flavors","displayName":"Fixed with Extra",
                      "price":{"kind":"FIXED","amount":"120.00","currency":"USD","futureField":"ignored"}}""",
                )
            additive.status shouldBe Status.CREATED
            additive
                .json()["offering"]!!
                .jsonObject["price"]!!
                .jsonObject.keys shouldBe setOf("kind", "amount", "currency")
            request(Method.GET, "/catalog-a/offerings/fixed-with-extra")
                .json()["offering"]!!
                .jsonObject["price"]!!
                .jsonObject.keys shouldBe setOf("kind", "amount", "currency")
            val conflicting =
                request(
                    Method.POST,
                    "/catalog-a/offerings",
                    """{"key":"fixed-with-dimension","category":"flavors","displayName":"Fixed with Dimension",
                      "price":{"kind":"FIXED","amount":"120.00","currency":"USD","dimension":"guest"}}""",
                )
            conflicting.status shouldBe Status.UNPROCESSABLE_ENTITY
            conflicting.json()["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
            priceShapes.forEachIndexed { index, (price, status) ->
                val body = """{"key":"shape$index","category":"flavors","displayName":"Shape $index","price":$price}"""
                request(Method.POST, "/catalog-a/offerings", body).status shouldBe status
            }
            val openapi = request(Method.GET, "/openapi.json").json()
            val paths = openapi["paths"]!!.jsonObject
            paths.containsKey("/host") shouldBe true
            paths.containsKey("/catalog-a") shouldBe true
            paths.containsKey("/catalog-b") shouldBe true
            listOf(
                "/catalog-a/revisions/{revision}",
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
        }

        test("OpenAPI price union and every request and response reference match runtime validation") {
            val document = request(Method.GET, "/openapi.json").json()
            val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
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
                Triple("/catalog-a/offerings", "post", "201"),
                Triple("/catalog-a/offerings/{offeringKey}", "get", "200"),
                Triple("/catalog-a/offerings", "get", "200"),
                Triple("/catalog-a/categories/{categoryKey}/offerings", "get", "200"),
                Triple("/catalog-a", "get", "200"),
                Triple("/catalog-a", "post", "201"),
                Triple("/catalog-a/revisions/{revision}", "get", "200"),
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

            val initialized = responseExample("/catalog-a", "post", "201")
            initialized["catalogId"]!!.jsonPrimitive.content shouldBe catalogA.value.toString()
            initialized["revision"]!!.jsonPrimitive.content shouldBe "1"
            (initialized["previousRevision"] ?: JsonNull) shouldBe JsonNull
            initialized["categories"]!!.jsonArray.size shouldBe 0
            listOf(
                Triple("/catalog-a/categories", "post", "201") to 2,
                Triple("/catalog-a/offerings", "post", "201") to 3,
                Triple("/catalog-a", "get", "200") to 3,
                Triple("/catalog-a/revisions/{revision}", "get", "200") to 3,
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

        test("path parameter failures match each route's documented error statuses") {
            val paths = request(Method.GET, "/openapi.json").json()["paths"]!!.jsonObject
            paths["/catalog-a/revisions/{revision}"]!!
                .jsonObject["get"]!!
                .jsonObject["parameters"]!!
                .jsonArray
                .single()
                .jsonObject["schema"]!!
                .jsonObject["type"]!!
                .jsonPrimitive.content shouldBe "integer"
            mapOf(
                "/catalog-a/revisions/{revision}" to
                    listOf(
                        "/catalog-a/revisions/abc" to "malformed_request",
                        "/catalog-a/revisions/1.5" to "malformed_request",
                        "/catalog-a/revisions/0" to "validation_failed",
                        "/catalog-a/revisions/-1" to "validation_failed",
                        "/catalog-a/revisions/999" to "not_found",
                    ),
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
