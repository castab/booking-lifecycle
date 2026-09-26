package io.github.castab.commerce.runtime

import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.customer.CustomerResponse
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import java.util.UUID

/**
 * The runtime as a concrete application composes it: explicit application contributions,
 * then configuration, pool, migrations (runtime, then application), JDBI,
 * operations, routes, error handling, and Jetty, exercised over real HTTP.
 */
class CommerceRuntimeSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var runtime: CommerceRuntime
        lateinit var http: HttpHandler

        beforeSpec {
            database = TestDatabase.create()
            val configuration =
                CommerceRuntimeConfiguration(
                    server = CommerceRuntimeConfiguration.Server(port = 0),
                    database = database.configuration,
                    migrations =
                        CommerceRuntimeConfiguration.Migrations(
                            onStartup = CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE,
                        ),
                )
            // An application contribution that shares the runtime's transaction boundary with a
            // commerce repository, as a concrete application's own capability would.
            val application =
                ApplicationContributions(
                    migrationLocations = listOf("classpath:db/testapp"),
                    routes = { context ->
                        listOf(
                            "/test-application/customers/{id}/notes" bind Method.POST to { request ->
                                val id = Customer.Id(UUID.fromString(request.path("id")))
                                context.transactor.inTransaction { transaction ->
                                    context.customers.find(transaction, id) ?: return@inTransaction Response(Status.NOT_FOUND)
                                    transaction.handle
                                        .createUpdate(
                                            "INSERT INTO public.test_application_customer_notes (customer_id, note) VALUES (:id, :note)",
                                        ).bind("id", id.value)
                                        .bind("note", request.bodyString())
                                        .execute()
                                    Response(Status.NO_CONTENT)
                                }
                            },
                        )
                    },
                )
            runtime = commerceRuntime(configuration, application).start()
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

        fun Response.customer() = CommerceJson.asA(bodyString(), CustomerResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun createCustomer(body: String) = http(Request(Method.POST, "/customers").header("Content-Type", "application/json").body(body))

        test("health and readiness are served") {
            http(Request(Method.GET, "/health")).status shouldBe Status.OK
            http(Request(Method.GET, "/ready")).bodyString() shouldBe """{"status":"ready"}"""
        }

        test("a customer is created and read back through the full stack") {
            val created = createCustomer("""{"name":"Ada Lovelace","email":"ada@example.com"}""")

            created.status shouldBe Status.CREATED
            val customer = created.customer()
            customer.id shouldMatch Regex("[0-9a-f-]{36}")
            customer.name shouldBe "Ada Lovelace"
            customer.email shouldBe "ada@example.com"
            created.header("Location") shouldBe "/customers/${customer.id}"

            val read = http(Request(Method.GET, "/customers/${customer.id}"))
            read.status shouldBe Status.OK
            read.customer() shouldBe customer
        }

        test("a domain validation failure is reported as validation_failed with the domain's message") {
            val response = createCustomer("""{"name":"  ","email":"ada@example.com"}""")

            response.status shouldBe Status.UNPROCESSABLE_ENTITY
            response.error() shouldBe ErrorResponse("validation_failed", "Customer name must not be blank")
        }

        test("an unreadable request is malformed") {
            createCustomer("""{"name":"Ada Lovelace"}""").error().code shouldBe "malformed_request"
            http(Request(Method.GET, "/customers/not-a-uuid")).status shouldBe Status.BAD_REQUEST
        }

        test("an unknown customer is not found") {
            val id = UUID.randomUUID()
            val response = http(Request(Method.GET, "/customers/$id"))

            response.status shouldBe Status.NOT_FOUND
            response.error() shouldBe ErrorResponse("not_found", "Customer $id was not found")
        }

        test("application routes and migrations are composed into the same runtime") {
            val customer = createCustomer("""{"name":"Grace Hopper","email":"grace@example.com"}""").customer()

            http(Request(Method.POST, "/test-application/customers/${customer.id}/notes").body("prefers email")).status shouldBe
                Status.NO_CONTENT
            http(Request(Method.GET, "/no-such-route")).error().code shouldBe "not_found"
        }
    })
