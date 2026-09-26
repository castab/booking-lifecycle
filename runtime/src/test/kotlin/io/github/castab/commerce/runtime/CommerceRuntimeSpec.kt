package io.github.castab.commerce.runtime

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.Serializable
import org.http4k.client.JavaHttpClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.util.UUID

/** A test application's own request body. */
@Serializable
private data class RecordRequest(
    val value: String,
)

/** A test application's own response body. */
@Serializable
private data class RecordResponse(
    val id: String,
    val value: String,
)

private val recordRequest = jsonBody(RecordRequest.serializer())
private val recordResponse = jsonBody(RecordResponse.serializer())
private val recordId = Path.uuid().of("id")

private fun insertRecord(
    transaction: Transaction,
    value: String,
): UUID =
    UUID.randomUUID().also { id ->
        transaction.handle
            .createUpdate("INSERT INTO public.test_application_records (id, value) VALUES (:id, :value)")
            .bind("id", id)
            .bind("value", value)
            .execute()
    }

private fun findRecord(
    transaction: Transaction,
    id: UUID,
): String? =
    transaction.handle
        .createQuery("SELECT value FROM public.test_application_records WHERE id = :id")
        .bind("id", id)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

/**
 * The routes of a test-only concrete application. They persist the application's own
 * table (migrated from `db/testapp`) through the runtime's shared [CommerceRuntimeContext]
 * transactor, as a real application's capability would. Nothing here depends on a commerce
 * table.
 */
private fun testApplicationRoutes(context: CommerceRuntimeContext): RoutingHttpHandler =
    routes(
        "/test-application/records" bind Method.POST to { request ->
            val value = validating { recordRequest(request).value.also { require(it.isNotBlank()) { "Record value must not be blank" } } }
            val id = context.transactor.inTransaction { transaction -> insertRecord(transaction, value) }
            Response(Status.CREATED).with(recordResponse of RecordResponse(id.toString(), value))
        },
        "/test-application/records/{id}" bind Method.GET to { request ->
            val id = recordId(request)
            val value =
                context.transactor.inTransaction { transaction -> findRecord(transaction, id) }
                    ?: throw CommerceFailure.NotFound("Record $id was not found")
            Response(Status.OK).with(recordResponse of RecordResponse(id.toString(), value))
        },
        "/test-application/records/failing" bind Method.POST to { request ->
            context.transactor.inTransaction { transaction ->
                insertRecord(transaction, recordRequest(request).value)
                error("relation \"secret_internal_table\" rejected the write")
            }
        },
    )

/**
 * The runtime as a concrete application composes it: explicit application contributions
 * (an application migration and application routes), then configuration, pool, the
 * migration phase (runtime, then application), JDBI, the shared transaction boundary, the
 * runtime's infrastructure routes, error handling, and Jetty, exercised over real HTTP.
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
            val application =
                ApplicationContributions(
                    migrationLocations = listOf("classpath:db/testapp"),
                    routes = { context -> listOf(testApplicationRoutes(context)) },
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

        fun Response.record() = CommerceJson.asA(bodyString(), RecordResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun createRecord(body: String) =
            http(Request(Method.POST, "/test-application/records").header("Content-Type", "application/json").body(body))

        test("health and readiness are served by the runtime") {
            http(Request(Method.GET, "/health")).bodyString() shouldBe """{"status":"ok"}"""
            http(Request(Method.GET, "/ready")).bodyString() shouldBe """{"status":"ready"}"""
        }

        test("a contributed route persists through the shared transactor into a contributed migration's table") {
            val created = createRecord("""{"value":"application value"}""")

            created.status shouldBe Status.CREATED
            val record = created.record()
            record.value shouldBe "application value"

            val read = http(Request(Method.GET, "/test-application/records/${record.id}"))
            read.status shouldBe Status.OK
            read.record() shouldBe record
        }

        test("runtime error handling wraps application routes") {
            createRecord("""{"value":"  "}""").let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "Record value must not be blank")
            }
            createRecord("""{}""").error().code shouldBe "malformed_request"
            http(Request(Method.GET, "/test-application/records/not-a-uuid")).status shouldBe Status.BAD_REQUEST

            val missing = UUID.randomUUID()
            http(Request(Method.GET, "/test-application/records/$missing")).let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Record $missing was not found")
            }
        }

        test("an unexpected failure in an application route rolls back its transaction and leaks nothing") {
            val before = TestRecords.count(database)

            val response =
                http(
                    Request(Method.POST, "/test-application/records/failing")
                        .header("Content-Type", "application/json")
                        .body("""{"value":"never committed"}"""),
                )

            response.status shouldBe Status.INTERNAL_SERVER_ERROR
            response.error() shouldBe ErrorResponse("internal_failure", "The request could not be completed")
            response.bodyString() shouldNotContain "secret_internal_table"
            TestRecords.count(database) shouldBe before
        }

        test("the runtime serves no customer or other application data endpoints of its own") {
            http(Request(Method.POST, "/customers").body("""{"name":"Ada","email":"ada@example.com"}""")).error().code shouldBe
                "not_found"
            http(Request(Method.GET, "/customers/${UUID.randomUUID()}")).error().code shouldBe "not_found"
            http(Request(Method.GET, "/no-such-route")).error().code shouldBe "not_found"
        }
    })

/** Direct reads of the test application's table, outside the runtime under test. */
private object TestRecords {
    fun count(database: TestDatabase): Int =
        java.sql.DriverManager
            .getConnection(database.configuration.jdbcUrl, database.configuration.username, database.configuration.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM public.test_application_records").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
}
