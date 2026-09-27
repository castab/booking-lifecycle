package io.github.castab.commerce.runtime.offering

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
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

class OfferingsCapabilitySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var runtime: CommerceRuntime
        lateinit var context: CommerceRuntimeContext
        lateinit var http: HttpHandler
        val catalogA = OfferingsCatalogId(UUID.randomUUID())
        val catalogB = OfferingsCatalogId(UUID.randomUUID())

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
                            val a =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogA, "/catalog-a", "catalogA", OfferingsHttpAccess.READ_WRITE),
                                )
                            val b =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogB, "/catalog-b", "catalogB", OfferingsHttpAccess.READ_WRITE),
                                )
                            val readOnly =
                                offeringsHttpCapability(
                                    supplied,
                                    OfferingsHttpBinding(catalogA, "/catalog-ro", "catalogRo", OfferingsHttpAccess.READ_ONLY),
                                )
                            val host =
                                contract {
                                    renderer = OpenApi3(ApiInfo("Host API", "1"), Jackson, apiRenderer = offeringsOpenApiRenderer(Jackson))
                                    descriptionPath = "/openapi.json"
                                    routes += a.contractRoutes
                                    routes += b.contractRoutes
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

        fun request(
            method: Method,
            path: String,
            body: String? = null,
        ): Response {
            val message = Request(method, path)
            return http(if (body == null) message else message.header("Content-Type", "application/json").body(body))
        }

        fun Response.json(): JsonObject = CommerceJson.parse(bodyString()).jsonObject

        test("bindings reject ambiguous paths and operation ID prefixes") {
            listOf("", "catalog", "/catalog/", "/catalog?x=1", "/catalog/{id}", "/catalog//nested").forEach { path ->
                shouldThrow<IllegalArgumentException> {
                    OfferingsHttpBinding(catalogA, path, "catalogA", OfferingsHttpAccess.READ_ONLY)
                }
            }
            listOf("", "with space", "9prefix", "a-b").forEach { prefix ->
                shouldThrow<IllegalArgumentException> {
                    OfferingsHttpBinding(catalogA, "/catalog", prefix, OfferingsHttpAccess.READ_ONLY)
                }
            }
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
                    OfferingPrice.Fixed(
                        io.github.castab.commerce.financial
                            .Money(BigDecimal("120.00"), Currency.getInstance("USD")),
                    ),
                    OfferingPrice.PerQuantity(
                        io.github.castab.commerce.financial
                            .Money(BigDecimal("0.75"), Currency.getInstance("USD")),
                        QuantityDimension("guest"),
                    ),
                    OfferingPrice.PerDuration(
                        io.github.castab.commerce.financial
                            .Money(BigDecimal("50.00"), Currency.getInstance("USD")),
                        Duration.ofNanos(123456789),
                    ),
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
            request(Method.GET, "/catalog-ro").status shouldBe Status.OK
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
            listOf("201", "400", "422", "404", "409").forEach { code ->
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
            listOf(
                Triple("FixedOfferingPrice", "FIXED", setOf("kind", "amount", "currency")),
                Triple("PerQuantityOfferingPrice", "PER_QUANTITY", setOf("kind", "amount", "currency", "dimension")),
                Triple("PerDurationOfferingPrice", "PER_DURATION", setOf("kind", "amount", "currency", "interval")),
            ).forEach { (name, kind, fields) ->
                val branch = schemas[name]!!.jsonObject
                branch["type"]!!.jsonPrimitive.content shouldBe "object"
                branch["additionalProperties"]!!.jsonPrimitive.content shouldBe "false"
                branch["properties"]!!.jsonObject.keys shouldBe fields
                branch["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet() shouldBe fields
                branch["example"]!!.jsonObject.keys shouldBe fields
                branch["properties"]!!
                    .jsonObject["kind"]!!
                    .jsonObject["enum"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content shouldBe kind
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
    })
