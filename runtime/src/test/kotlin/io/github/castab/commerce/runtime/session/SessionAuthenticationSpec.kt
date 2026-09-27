package io.github.castab.commerce.runtime.session

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.requirePermission
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresPrincipalSessionRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.MutableClock
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.cookies
import org.http4k.core.then
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.jdbi.v3.core.Jdbi
import java.time.Duration
import java.time.Instant
import java.util.UUID

private fun PrincipalId.text(): String =
    when (this) {
        is UserId -> "user:$value"
        is ServiceId -> "service:$value"
    }

/** Test handlers answer with the principal they received through the request context. */
private val echoPrincipal: HttpHandler = { request -> Response(Status.OK).body(authenticatedPrincipal(request).text()) }

/**
 * Request authentication through sessions and authorization through the domain's
 * `PermissionResolver`, over the real session store. Permissions are a mutable map the
 * test changes between requests, standing in for an application's role assignments.
 */
class SessionAuthenticationSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var sessions: SessionManager
        lateinit var http: HttpHandler
        val clock = MutableClock(Instant.parse("2026-09-26T12:00:00Z"))
        val lifetime = Duration.ofHours(1)
        val grants = mutableMapOf<PrincipalId, Set<PermissionKey>>()
        val permissionResolver = PermissionResolver { grants[it].orEmpty() }
        val sessionCookie = SessionCookie("__Host-test-session")

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "session-authentication-spec")
            MigrationLifecycle(dataSource, emptyList()).migrate()
            sessions = PersistentSessionManager(Transactor(Jdbi.create(dataSource)), PostgresPrincipalSessionRepository(), lifetime, clock)

            val access = AccessControl(sessionAuthentication(sessions, BearerSessionToken), permissionResolver)
            val cookieAccess = AccessControl(sessionAuthentication(sessions, sessionCookie), permissionResolver)
            http =
                CommerceErrorHandling.then(
                    routes(
                        "/public" bind Method.GET to access.public().then { Response(Status.OK).body("public") },
                        "/me" bind Method.GET to access.authenticated().then(echoPrincipal),
                        "/bookings" bind Method.GET to
                            access.requirePermission(CommercePermissions.BookingRead).then(echoPrincipal),
                        "/cookie/me" bind Method.GET to cookieAccess.authenticated().then(echoPrincipal),
                        // Permission checks without any authentication filter.
                        "/unauthenticated/bookings" bind Method.GET to
                            requirePermission(CommercePermissions.BookingRead, permissionResolver).then(echoPrincipal),
                        // A handler that reads the principal but was never protected.
                        "/unprotected" bind Method.GET to echoPrincipal,
                        // Authentication applied around a nested router.
                        "/nested" bind
                            sessionAuthentication(sessions, BearerSessionToken).then(
                                routes("/me" bind Method.GET to echoPrincipal),
                            ),
                    ),
                )
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun get(
            path: String,
            token: String? = null,
        ) = http(Request(Method.GET, path).let { if (token == null) it else it.header("Authorization", "Bearer $token") })

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun Response.shouldBeUnauthenticated() {
            status shouldBe Status.UNAUTHORIZED
            error() shouldBe ErrorResponse("unauthenticated", "Authentication is required")
        }

        fun Response.shouldBeForbidden() {
            status shouldBe Status.FORBIDDEN
            error().code shouldBe "forbidden"
        }

        context("authentication") {
            test("a missing token is unauthenticated") {
                get("/me").shouldBeUnauthenticated()
                http(Request(Method.GET, "/me").header("Authorization", "Bearer")).shouldBeUnauthenticated()
                http(Request(Method.GET, "/me").header("Authorization", "Basic dXNlcjpwYXNz")).shouldBeUnauthenticated()
            }

            test("malformed and unknown tokens are unauthenticated") {
                get("/me", "not-a-token").shouldBeUnauthenticated()
                get("/me", SessionToken.generate(java.security.SecureRandom()).value).shouldBeUnauthenticated()
            }

            test("a valid token makes its principal available to the handler") {
                val principal = UserId(UUID.randomUUID())
                val issued = sessions.create(principal)

                get("/me", issued.token.value).let {
                    it.status shouldBe Status.OK
                    it.bodyString() shouldBe principal.text()
                }
                http(Request(Method.GET, "/me").header("Authorization", "bearer ${issued.token.value}")).status shouldBe Status.OK
            }

            test("the principal survives routing when authentication wraps a nested router") {
                val principal = UserId(UUID.randomUUID())

                get("/nested/me", sessions.create(principal).token.value).bodyString() shouldBe principal.text()
                get("/nested/me").shouldBeUnauthenticated()
            }

            test("an expired session is unauthenticated") {
                val issued = sessions.create(UserId(UUID.randomUUID()))
                val start = clock.now

                clock.advance(lifetime)
                get("/me", issued.token.value).shouldBeUnauthenticated()

                clock.now = start
            }

            test("a revoked session is unauthenticated, and other sessions of the principal still work") {
                val principal = UserId(UUID.randomUUID())
                val revoked = sessions.create(principal)
                val kept = sessions.create(principal)

                sessions.revoke(revoked.token)

                get("/me", revoked.token.value).shouldBeUnauthenticated()
                get("/me", kept.token.value).status shouldBe Status.OK
            }

            test("session and principal identifiers cannot be used as tokens") {
                val principal = UserId(UUID.randomUUID())
                val issued = sessions.create(principal)

                get(
                    "/me",
                    issued.session.id.value
                        .toString(),
                ).shouldBeUnauthenticated()
                get("/me", principal.value.toString()).shouldBeUnauthenticated()
            }

            test("public routes need no token") {
                get("/public").let {
                    it.status shouldBe Status.OK
                    it.bodyString() shouldBe "public"
                }
            }

            test("a handler that reads the principal without protection fails closed") {
                get("/unprotected").shouldBeUnauthenticated()
                // Even with a valid token: nothing authenticated this route.
                get("/unprotected", sessions.create(UserId(UUID.randomUUID())).token.value).shouldBeUnauthenticated()
            }
        }

        context("authorization") {
            test("a principal holding the permission reaches the handler") {
                val principal = UserId(UUID.randomUUID())
                grants[principal] = setOf(CommercePermissions.BookingRead)

                get("/bookings", sessions.create(principal).token.value).let {
                    it.status shouldBe Status.OK
                    it.bodyString() shouldBe principal.text()
                }
            }

            test("a principal without the permission is forbidden") {
                val principal = UserId(UUID.randomUUID())
                grants[principal] = setOf(CommercePermissions.BookingModify)

                get("/bookings", sessions.create(principal).token.value).shouldBeForbidden()
            }

            test("no authenticated principal is unauthenticated, not forbidden") {
                get("/bookings").shouldBeUnauthenticated()
                get("/unauthenticated/bookings").shouldBeUnauthenticated()
            }

            test("authorization uses current permissions, not those held when the session began") {
                val principal = UserId(UUID.randomUUID())
                grants[principal] = setOf(CommercePermissions.BookingRead)
                val token = sessions.create(principal).token.value
                get("/bookings", token).status shouldBe Status.OK

                grants[principal] = emptySet()
                get("/bookings", token).shouldBeForbidden()

                grants[principal] = setOf(CommercePermissions.BookingRead)
                get("/bookings", token).status shouldBe Status.OK
            }

            test("users and services are authorized alike through their PrincipalId") {
                val service = ServiceId(UUID.randomUUID())
                val user = UserId(service.value)
                grants[service] = setOf(CommercePermissions.BookingRead)

                get("/bookings", sessions.create(service).token.value).let {
                    it.status shouldBe Status.OK
                    it.bodyString() shouldBe service.text()
                }
                // A user with the same UUID is a different principal with its own grants.
                get("/bookings", sessions.create(user).token.value).shouldBeForbidden()
            }
        }

        context("access control composition") {
            // Counts real session resolutions and permission evaluations.
            class Counting(
                private val delegate: SessionManager,
            ) : SessionManager by delegate {
                var resolutions = 0

                override fun resolve(token: SessionToken): PrincipalId? {
                    resolutions++
                    return delegate.resolve(token)
                }
            }

            fun counted(): Triple<Counting, AccessControl, () -> Int> {
                val counting = Counting(sessions)
                var evaluations = 0
                val resolver =
                    PermissionResolver {
                        evaluations++
                        permissionResolver.permissionsFor(it)
                    }
                return Triple(counting, AccessControl(sessionAuthentication(counting, BearerSessionToken), resolver), { evaluations })
            }

            fun Request.bearer(token: String) = header("Authorization", "Bearer $token")

            test("route-level: AccessControl authenticates once, then evaluates the permission") {
                val (counting, access, evaluations) = counted()
                val route = CommerceErrorHandling.then(access.requirePermission(CommercePermissions.BookingRead).then(echoPrincipal))
                val principal = UserId(UUID.randomUUID())
                grants[principal] = setOf(CommercePermissions.BookingRead)
                val token = sessions.create(principal).token.value

                route(Request(Method.GET, "/").bearer(token)).bodyString() shouldBe principal.text()
                counting.resolutions shouldBe 1
                evaluations() shouldBe 1

                route(Request(Method.GET, "/")).shouldBeUnauthenticated()
                grants[principal] = emptySet()
                route(Request(Method.GET, "/").bearer(token)).shouldBeForbidden()
            }

            test("outer authentication: AccessControl reuses the established principal and resolves the session once") {
                val (counting, access, evaluations) = counted()
                val application =
                    CommerceErrorHandling.then(
                        sessionAuthentication(counting, BearerSessionToken).then(
                            routes(
                                "/bookings" bind Method.GET to
                                    access.requirePermission(CommercePermissions.BookingRead).then(echoPrincipal),
                                "/me" bind Method.GET to access.authenticated().then(echoPrincipal),
                            ),
                        ),
                    )
                val principal = ServiceId(UUID.randomUUID())
                grants[principal] = setOf(CommercePermissions.BookingRead)
                val token = sessions.create(principal).token.value

                application(Request(Method.GET, "/bookings").bearer(token)).bodyString() shouldBe principal.text()
                counting.resolutions shouldBe 1
                evaluations() shouldBe 1
                application(Request(Method.GET, "/me").bearer(token)).bodyString() shouldBe principal.text()
                counting.resolutions shouldBe 2

                // Permissions are evaluated per request against the existing principal, never cached.
                grants[principal] = emptySet()
                application(Request(Method.GET, "/bookings").bearer(token)).shouldBeForbidden()
                counting.resolutions shouldBe 3
                evaluations() shouldBe 2

                application(Request(Method.GET, "/bookings")).shouldBeUnauthenticated()
                counting.resolutions shouldBe 3
            }
        }

        context("session cookies") {
            test("a session cookie authenticates like any other transport") {
                val principal = UserId(UUID.randomUUID())
                val issued = sessions.create(principal)

                http(Request(Method.GET, "/cookie/me").cookie(sessionCookie.issue(issued))).bodyString() shouldBe principal.text()
                http(Request(Method.GET, "/cookie/me").cookie(sessionCookie.name, "tampered")).shouldBeUnauthenticated()
                http(Request(Method.GET, "/cookie/me")).shouldBeUnauthenticated()
                // A bearer token does not satisfy a cookie-authenticated route.
                http(Request(Method.GET, "/cookie/me").header("Authorization", "Bearer ${issued.token.value}")).shouldBeUnauthenticated()
            }

            test("the issued cookie is secure, HTTP-only, host-only, and expires with the session") {
                val issued = sessions.create(UserId(UUID.randomUUID()))

                val cookie = sessionCookie.issue(issued)

                cookie.name shouldBe "__Host-test-session"
                cookie.value shouldBe issued.token.value
                cookie.secure shouldBe true
                cookie.httpOnly shouldBe true
                cookie.sameSite shouldBe SameSite.Lax
                cookie.path shouldBe "/"
                cookie.domain.shouldBeNull()
                cookie.expires shouldBe issued.session.expiresAt
            }

            test("clearing the cookie removes it from the browser") {
                val response = Response(Status.OK).cookie(sessionCookie.clear())

                val cleared = response.cookies().single()
                cleared.value shouldBe ""
                cleared.maxAge shouldBe 0
                cleared.secure shouldBe true
                cleared.httpOnly shouldBe true
            }

            test("cookie names and paths are validated") {
                shouldThrow<IllegalArgumentException> { SessionCookie("bad name") }
                shouldThrow<IllegalArgumentException> { SessionCookie("session", path = "relative") }
                shouldThrow<IllegalArgumentException> { SessionCookie("__Host-session", path = "/app") }
                SessionCookie("session", SameSite.Strict, "/app").toString() shouldBe
                    "SessionCookie(name=session, sameSite=Strict, path=/app)"
            }
        }
    })
