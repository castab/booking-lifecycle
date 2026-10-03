package io.github.castab.commerce.runtime

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.insertTestPrincipal
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.UserId
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
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.math.BigDecimal
import java.util.Currency
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
            .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
            .bind("id", id)
            .bind("value", value)
            .execute()
    }

private fun findRecord(
    transaction: Transaction,
    id: UUID,
): String? =
    transaction.handle
        .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
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

/** A test application's login request: a username only, because identity proof is the application's concern. */
@Serializable
private data class LoginRequest(
    val username: String,
)

@Serializable
private data class LoginResponse(
    val token: String,
)

@Serializable
private data class PrincipalResponse(
    val userId: String,
)

private val loginRequest = jsonBody(LoginRequest.serializer())
private val loginResponse = jsonBody(LoginResponse.serializer())
private val principalResponse = jsonBody(PrincipalResponse.serializer())

/**
 * The test application's own identities. A real application verifies credentials
 * (a password hash, OAuth, a passkey, ...) before it trusts a username; this fake step stands
 * in for that, so the runtime never sees a credential.
 */
private object TestIdentities {
    val reader = UserId(UUID.randomUUID())
    val visitor = UserId(UUID.randomUUID())

    fun authenticate(username: String): UserId? =
        when (username) {
            "reader" -> reader
            "visitor" -> visitor
            else -> null
        }

    /** A runtime role the application defines and assigns; the visitor holds no grant. */
    val bookingReader = RoleDefinition(RoleKey("test.booking-reader"), "Booking reader", null, setOf(CommercePermissions.BookingRead))
}

/**
 * Authentication routes of the test application, built on the runtime's sessions: the
 * application proves identity, the runtime issues and resolves the session.
 */
private fun testAuthenticationRoutes(context: CommerceRuntimeContext): RoutingHttpHandler {
    val access =
        AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), context.authorization)
    val principal = { request: Request ->
        val userId = authenticatedPrincipal(request) as UserId
        Response(Status.OK).with(principalResponse of PrincipalResponse(userId.value.toString()))
    }
    return routes(
        "/test-application/login" bind Method.POST to
            access.public().then { request ->
                val userId =
                    TestIdentities.authenticate(loginRequest(request).username)
                        ?: return@then Response(Status.UNAUTHORIZED)
                val issued = context.sessions.create(userId)
                // The application decides how the token reaches its client; here, a JSON body.
                Response(Status.OK).with(loginResponse of LoginResponse(issued.token.value))
            },
        "/test-application/me" bind Method.GET to access.authenticated().then(principal),
        "/test-application/bookings" bind Method.GET to access.requirePermission(CommercePermissions.BookingRead).then(principal),
        "/test-application/logout" bind Method.POST to
            access.authenticated().then { request ->
                context.sessions.revoke(BearerSessionToken.extract(request)!!)
                Response(Status.NO_CONTENT)
            },
    )
}

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
        lateinit var context: CommerceRuntimeContext

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
                    migrations = testApplicationMigrations("classpath:db/testapp"),
                    routes = { suppliedContext ->
                        context = suppliedContext
                        listOf(testApplicationRoutes(suppliedContext), testAuthenticationRoutes(suppliedContext))
                    },
                )
            runtime = commerceRuntime(configuration, application).start()
            context.transactor.insertTestPrincipal(TestIdentities.reader)
            context.transactor.insertTestPrincipal(TestIdentities.visitor)
            // Authorization is the runtime's: a real role definition and assignment, resolved live.
            context.authorization.createRole(TestIdentities.bookingReader)
            context.authorization.assignRole(TestIdentities.reader, TestIdentities.bookingReader.key)
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

        test("application contributions receive the commerce offerings repository in their runtime context") {
            val snapshot = OfferingsSnapshot.create(OfferingsCatalogId(UUID.randomUUID()))
            context.transactor.inTransaction { transaction ->
                context.offeringsSnapshotRepository.save(transaction, snapshot)
            }
            context.transactor.inTransaction { transaction ->
                context.offeringsSnapshotRepository.retrieveLatestVersion(transaction, snapshot.catalogId)
            } shouldBe snapshot
        }

        test("application contributions can atomically write a financial snapshot and their own relationship") {
            val usd = Currency.getInstance("USD")
            val estimate =
                FinancialDocument.Estimate.create(
                    UUID.randomUUID(),
                    listOf(
                        LineItem(
                            UUID.randomUUID(),
                            "Service",
                            quantity = null,
                            price = Money(BigDecimal("25.00"), usd),
                            taxAmount = Money(BigDecimal("0.00"), usd),
                        ),
                    ),
                )
            val relationshipId =
                context.transactor.inTransaction { transaction ->
                    context.financialLedger.create(transaction, estimate)
                    insertRecord(transaction, estimate.id.toString())
                }
            context.financialLedger.get(estimate.reference) shouldBe estimate
            context.transactor.inTransaction { transaction -> findRecord(transaction, relationshipId) } shouldBe estimate.id.toString()
        }

        test("an application authenticates identity itself, then the runtime's session carries it over HTTP") {
            val login =
                http(
                    Request(
                        Method.POST,
                        "/test-application/login",
                    ).header("Content-Type", "application/json").body("""{"username":"reader"}"""),
                )
            login.status shouldBe Status.OK
            val token = CommerceJson.asA(login.bodyString(), LoginResponse.serializer()).token

            fun authenticated(
                method: Method,
                path: String,
            ) = http(Request(method, path).header("Authorization", "Bearer $token"))

            val me = authenticated(Method.GET, "/test-application/me")
            me.status shouldBe Status.OK
            CommerceJson.asA(me.bodyString(), PrincipalResponse.serializer()).userId shouldBe TestIdentities.reader.value.toString()
            authenticated(Method.GET, "/test-application/bookings").status shouldBe Status.OK

            authenticated(Method.POST, "/test-application/logout").status shouldBe Status.NO_CONTENT
            authenticated(Method.GET, "/test-application/me").let {
                it.status shouldBe Status.UNAUTHORIZED
                it.error().code shouldBe "unauthenticated"
            }
        }

        test("the application's identities, the runtime's sessions, and the runtime's live grants stay separate") {
            http(
                Request(
                    Method.POST,
                    "/test-application/login",
                ).header("Content-Type", "application/json").body("""{"username":"nobody"}"""),
            ).status shouldBe Status.UNAUTHORIZED

            val visitor = context.sessions.create(TestIdentities.visitor)
            http(Request(Method.GET, "/test-application/me").header("Authorization", "Bearer ${visitor.token.value}")).status shouldBe
                Status.OK
            http(Request(Method.GET, "/test-application/bookings").header("Authorization", "Bearer ${visitor.token.value}")).let {
                it.status shouldBe Status.FORBIDDEN
                it.error().code shouldBe "forbidden"
            }

            // Grants are resolved live: the same session gains and loses access with the assignment.
            context.authorization.assignRole(TestIdentities.visitor, TestIdentities.bookingReader.key)
            http(Request(Method.GET, "/test-application/bookings").header("Authorization", "Bearer ${visitor.token.value}")).status shouldBe
                Status.OK
            context.authorization.unassignRole(TestIdentities.visitor, TestIdentities.bookingReader.key)
            http(Request(Method.GET, "/test-application/bookings").header("Authorization", "Bearer ${visitor.token.value}")).status shouldBe
                Status.FORBIDDEN
            http(Request(Method.GET, "/test-application/bookings")).status shouldBe Status.UNAUTHORIZED
        }

        test("health and readiness stay public") {
            http(Request(Method.GET, "/health")).status shouldBe Status.OK
            http(Request(Method.GET, "/ready").header("Authorization", "Bearer not-a-token")).status shouldBe Status.OK
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
            http(Request(Method.GET, "/offerings")).error().code shouldBe "not_found"
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
                    statement.executeQuery("SELECT count(*) FROM testapp.test_application_records").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
}
