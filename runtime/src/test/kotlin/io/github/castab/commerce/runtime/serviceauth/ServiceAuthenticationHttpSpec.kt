package io.github.castab.commerce.runtime.serviceauth

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.authorization.RuntimePermissions
import io.github.castab.commerce.runtime.authorization.authorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.SessionAuthenticator
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testServiceTokens
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.cookies
import org.http4k.core.then
import org.http4k.format.Jackson
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.time.Instant
import java.util.UUID

private fun PrincipalId.text(): String =
    when (this) {
        is UserId -> "user:$value"
        is ServiceId -> "service:$value"
    }

private val echoPrincipal: HttpHandler = { request -> Response(Status.OK).body(authenticatedPrincipal(request).text()) }

private fun TestDatabase.runtimeConfiguration(serviceTokens: CommerceRuntimeConfiguration.ServiceTokens?) =
    CommerceRuntimeConfiguration(
        server = CommerceRuntimeConfiguration.Server(0),
        database = configuration,
        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
        serviceTokens = serviceTokens,
    )

/**
 * Service authentication composed as a concrete application composes it: the runtime's
 * administration capability, its token endpoint, and an application route that accepts a
 * user's cookie session or a service's access token through one [AccessControl].
 */
class ServiceAuthenticationHttpSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var runtime: CommerceRuntime
        lateinit var context: CommerceRuntimeContext
        lateinit var openApi: HttpHandler
        val cookie = SessionCookie("__Host-test-session")
        val admin = "/admin/access"

        beforeSpec {
            database = TestDatabase.create()
            runtime =
                commerceRuntime(
                    database.runtimeConfiguration(testServiceTokens()),
                    ApplicationContributions(
                        routes = { supplied ->
                            context = supplied
                            val authenticate =
                                authentication(
                                    SessionAuthenticator(supplied.sessions, cookie),
                                    SessionAuthenticator(supplied.sessions, BearerSessionToken),
                                    ServiceAccessTokenAuthenticator(supplied.serviceAccessTokens),
                                )
                            val access = AccessControl(authenticate, supplied.authorization)
                            openApi =
                                contract {
                                    renderer = OpenApi3(ApiInfo("Service authentication", "1"), Jackson)
                                    descriptionPath = "/openapi.json"
                                    security = serviceAccessTokenOpenApiSecurity
                                    routes += authorizationAdministrationHttpCapability(supplied, access, admin).contractRoutes
                                    routes += serviceAuthenticationHttpCapability(supplied, "/auth/service/token").contractRoutes
                                }
                            listOf(
                                openApi,
                                routes(
                                    // The application's own login: it proves a user's identity, then the runtime issues a session.
                                    "/app/login/{userId}" bind Method.POST to { request ->
                                        val user = UserId(UUID.fromString(request.uri.path.substringAfterLast('/')))
                                        Response(Status.NO_CONTENT).cookie(cookie.issue(supplied.sessions.create(user)))
                                    },
                                    "/app/logout" bind Method.POST to
                                        access.authenticated().then { request ->
                                            cookie.extract(request)?.let(supplied.sessions::revoke)
                                            Response(Status.NO_CONTENT).cookie(cookie.clear())
                                        },
                                    "/app/me" bind Method.GET to access.authenticated().then(echoPrincipal),
                                    "/app/bookings" bind Method.GET to
                                        access.requirePermission(CommercePermissions.BookingRead).then(echoPrincipal),
                                    "/app/cookie-only/me" bind Method.GET to
                                        sessionAuthentication(supplied.sessions, cookie).then(echoPrincipal),
                                    "/app/service-only/me" bind Method.GET to
                                        serviceAccessTokenAuthentication(supplied.serviceAccessTokens).then(echoPrincipal),
                                ),
                            )
                        },
                    ),
                )
        }

        afterSpec {
            runtime.close()
            database.close()
        }

        fun user(name: String) =
            context.authorization.createUser(User(UserId(UUID.randomUUID()), name, null, null, name, PrincipalStatus.ACTIVE, emptySet()))

        fun userWith(
            name: String,
            permissions: Set<PermissionKey>,
        ): UserId {
            val created = user(name)
            val role = RoleKey("test.$name")
            context.authorization.createRole(RoleDefinition(role, name, null, permissions))
            context.authorization.assignRole(created.id, role)
            return created.id
        }

        fun login(user: UserId): Cookie =
            runtime
                .http(Request(Method.POST, "/app/login/${user.value}"))
                .cookies()
                .single()

        fun request(
            method: Method,
            path: String,
            bearer: String? = null,
            session: Cookie? = null,
            body: String? = null,
        ) = runtime.http(
            Request(method, path)
                .let { if (bearer == null) it else it.header("Authorization", "Bearer $bearer") }
                .let { if (session == null) it else it.cookie(session) }
                .let { if (body == null) it else it.header("Content-Type", "application/json").body(body) },
        )

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun Response.json() = Json.parseToJsonElement(bodyString()).jsonObject

        val administrator by lazy {
            login(
                userWith(
                    "administrator",
                    setOf(
                        CommercePermissions.PrincipalRead,
                        CommercePermissions.PrincipalManage,
                        CommercePermissions.RoleRead,
                        CommercePermissions.RoleManage,
                        CommercePermissions.RoleAssign,
                        RuntimePermissions.ServiceCredentialManage,
                    ),
                ),
            )
        }

        fun createService(name: String): ServiceId =
            ServiceId(
                UUID.fromString(
                    request(Method.POST, "$admin/services", session = administrator, body = """{"name":"$name"}""")
                        .json()["id"]!!
                        .jsonPrimitive.content,
                ),
            )

        fun createCredential(
            service: ServiceId,
            label: String = "deploy",
        ): JsonObject {
            val response =
                request(
                    Method.POST,
                    "$admin/services/${service.value}/credentials",
                    session = administrator,
                    body = """{"label":"$label"}""",
                )
            response.status shouldBe Status.CREATED
            return response.json()
        }

        fun token(
            service: ServiceId,
            secret: String,
        ) = request(Method.POST, "/auth/service/token", body = """{"serviceId":"${service.value}","secret":"$secret"}""")

        fun accessToken(
            service: ServiceId,
            secret: String,
        ): String = token(service, secret).json()["accessToken"]!!.jsonPrimitive.content

        context("token endpoint") {
            test("a service exchanges its id and secret for a bearer token with an expiry") {
                val service = createService("token-success")
                val secret = createCredential(service)["secret"]!!.jsonPrimitive.content

                val response = token(service, secret)

                response.status shouldBe Status.OK
                response.header("Cache-Control") shouldBe "no-store"
                val body = response.json()
                body["tokenType"]!!.jsonPrimitive.content shouldBe "Bearer"
                body["expiresIn"]!!.jsonPrimitive.content shouldBe "900"
                Instant.parse(body["expiresAt"]!!.jsonPrimitive.content).isAfter(Instant.now()) shouldBe true
                response.bodyString() shouldNotContain secret
                request(Method.GET, "/app/me", bearer = body["accessToken"]!!.jsonPrimitive.content).bodyString() shouldBe service.text()
            }

            test("every authentication failure is the same 401") {
                val service = createService("token-failures")
                val credential = createCredential(service)
                val secret = credential["secret"]!!.jsonPrimitive.content
                val revoked = createCredential(service, "revoked")
                request(
                    Method.DELETE,
                    "$admin/services/${service.value}/credentials/${revoked["credentialId"]!!.jsonPrimitive.content}",
                    session = administrator,
                ).status shouldBe Status.NO_CONTENT
                val disabled = createService("token-disabled")
                val disabledSecret = createCredential(disabled)["secret"]!!.jsonPrimitive.content
                request(Method.PUT, "$admin/services/${disabled.value}/status", session = administrator, body = """{"status":"DISABLED"}""")
                val wrongSecret = secret.substringBefore('.') + "." + "A".repeat(42) + "A"
                val userId = user("token-user").id

                listOf(
                    token(ServiceId(UUID.randomUUID()), secret),
                    token(service, wrongSecret),
                    token(service, revoked["secret"]!!.jsonPrimitive.content),
                    token(service, "not-a-secret"),
                    token(disabled, disabledSecret),
                    token(ServiceId(userId.value), secret),
                ).forEach { response ->
                    response.status shouldBe Status.UNAUTHORIZED
                    response.error() shouldBe ErrorResponse("unauthenticated", "Service authentication failed")
                    response.header("Cache-Control") shouldBe "no-store"
                    response.bodyString() shouldNotContain secret.substringAfter('.')
                }
                token(service, secret).status shouldBe Status.OK
            }

            test("malformed requests are rejected cleanly") {
                listOf("""{}""", """not json""", """{"serviceId":"x"}""", """{"serviceId":"not-a-uuid","secret":"s"}""").forEach { body ->
                    request(Method.POST, "/auth/service/token", body = body).let {
                        it.status shouldBe Status.BAD_REQUEST
                        it.error().code shouldBe "malformed_request"
                    }
                }
            }
        }

        context("authorization comes from current roles, never from the token") {
            test("a token alone grants nothing; a role grant and removal apply to the same token immediately") {
                val service = createService("worker")
                val token = accessToken(service, createCredential(service)["secret"]!!.jsonPrimitive.content)

                // Authenticated as the service, not as a user, and without the permission.
                request(Method.GET, "/app/me", bearer = token).bodyString() shouldBe service.text()
                request(Method.GET, "/app/bookings", bearer = token).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().code shouldBe "forbidden"
                }

                request(
                    Method.POST,
                    "$admin/roles",
                    session = administrator,
                    body =
                        """{"key":"test.booking-reader","displayName":"Booking reader","description":null,""" +
                            """"permissions":["commerce.booking.read"]}""",
                ).status shouldBe Status.CREATED
                request(Method.PUT, "$admin/services/${service.value}/roles/test.booking-reader", session = administrator).status shouldBe
                    Status.NO_CONTENT
                request(Method.GET, "/app/bookings", bearer = token).let {
                    it.status shouldBe Status.OK
                    it.bodyString() shouldBe service.text()
                }

                request(
                    Method.DELETE,
                    "$admin/services/${service.value}/roles/test.booking-reader",
                    session = administrator,
                ).status shouldBe
                    Status.NO_CONTENT
                request(Method.GET, "/app/bookings", bearer = token).status shouldBe Status.FORBIDDEN

                // Disabling the service suspends its unexpired token at once...
                request(Method.PUT, "$admin/services/${service.value}/status", session = administrator, body = """{"status":"DISABLED"}""")
                    .status shouldBe Status.OK
                request(Method.GET, "/app/me", bearer = token).status shouldBe Status.UNAUTHORIZED
                // ...but does not revoke it: activating the service again restores the same token.
                request(Method.PUT, "$admin/services/${service.value}/status", session = administrator, body = """{"status":"ACTIVE"}""")
                    .status shouldBe Status.OK
                request(Method.GET, "/app/me", bearer = token).bodyString() shouldBe service.text()
            }

            test("401 without valid authentication, 403 with authentication but without permission") {
                request(Method.GET, "/app/bookings").status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/bookings", bearer = "not-a-token").status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/bookings", bearer = "a.b.c").status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/bookings", session = login(user("no-permission").id)).status shouldBe Status.FORBIDDEN
            }
        }

        context("user sessions keep working alongside service tokens") {
            test("login, cookie authentication, permission enforcement, and logout") {
                val reader = userWith("reader", setOf(CommercePermissions.BookingRead))
                val session = login(reader)

                request(Method.GET, "/app/me", session = session).bodyString() shouldBe reader.text()
                request(Method.GET, "/app/bookings", session = session).status shouldBe Status.OK
                request(Method.GET, "/app/cookie-only/me", session = session).status shouldBe Status.OK

                request(Method.POST, "/app/logout", session = session).let {
                    it.status shouldBe Status.NO_CONTENT
                    it.cookies().single().maxAge shouldBe 0
                }
                request(Method.GET, "/app/me", session = session).status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/bookings", session = session).status shouldBe Status.UNAUTHORIZED
            }

            test("each mechanism accepts only its own credentials") {
                val service = createService("mechanisms")
                val serviceToken = accessToken(service, createCredential(service)["secret"]!!.jsonPrimitive.content)
                val userId = user("mechanisms-user").id
                val sessionToken =
                    context.sessions
                        .create(userId)
                        .token.value

                request(Method.GET, "/app/me", bearer = sessionToken).bodyString() shouldBe userId.text()
                request(Method.GET, "/app/me", bearer = serviceToken).bodyString() shouldBe service.text()
                // A cookie-only route ignores a service token; a service-only route ignores a session token.
                request(Method.GET, "/app/cookie-only/me", bearer = serviceToken).status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/service-only/me", bearer = sessionToken).status shouldBe Status.UNAUTHORIZED
                request(Method.GET, "/app/service-only/me", bearer = serviceToken).bodyString() shouldBe service.text()
                // Mechanisms are tried in order and the first that proves a principal wins.
                request(Method.GET, "/app/me", bearer = serviceToken, session = login(userId)).bodyString() shouldBe userId.text()
            }

            test("an authentication filter needs at least one mechanism") {
                shouldThrow<IllegalArgumentException> { authentication() }
            }
        }

        context("credential administration") {
            test("creation returns the secret once; listing returns metadata only") {
                val service = createService("administered")
                val created = createCredential(service, "primary")
                val secret = created["secret"]!!.jsonPrimitive.content
                created.keys shouldBe setOf("credentialId", "serviceId", "label", "createdAt", "secret")
                createCredential(service, "secondary")

                val listed = request(Method.GET, "$admin/services/${service.value}/credentials", session = administrator)
                listed.status shouldBe Status.OK
                listed.bodyString() shouldNotContain secret.substringAfter('.')
                listed.bodyString() shouldNotContain "argon2"
                listed.bodyString() shouldNotContain "secret"
                val credentials = listed.json()["credentials"]!!.jsonArray.map { it.jsonObject }
                credentials.map { it["label"]!!.jsonPrimitive.content } shouldBe listOf("primary", "secondary")
                credentials.forEach { it["revoked"]!!.jsonPrimitive.content shouldBe "false" }

                val path = "$admin/services/${service.value}/credentials/${created["credentialId"]!!.jsonPrimitive.content}"
                request(Method.DELETE, path, session = administrator).status shouldBe Status.NO_CONTENT
                request(Method.DELETE, path, session = administrator).status shouldBe Status.NO_CONTENT
                request(Method.GET, "$admin/services/${service.value}/credentials", session = administrator)
                    .json()["credentials"]!!
                    .jsonArray
                    .first()
                    .jsonObject
                    .let {
                        it["revoked"]!!.jsonPrimitive.content shouldBe "true"
                        it.containsKey("revokedAt") shouldBe true
                    }
            }

            test("credential administration requires its own permission") {
                val service = createService("guarded")
                val credential = createCredential(service)["credentialId"]!!.jsonPrimitive.content
                val principalManager =
                    login(userWith("principal-manager", setOf(CommercePermissions.PrincipalRead, CommercePermissions.PrincipalManage)))
                val nobody = login(user("nobody").id)
                val credentials = "$admin/services/${service.value}/credentials"

                request(Method.GET, credentials).status shouldBe Status.UNAUTHORIZED
                request(Method.POST, credentials, body = """{"label":"x"}""").status shouldBe Status.UNAUTHORIZED
                request(Method.DELETE, "$credentials/$credential").status shouldBe Status.UNAUTHORIZED
                request(Method.GET, credentials, session = nobody).status shouldBe Status.FORBIDDEN
                // Managing principals does not include acting as a service.
                request(Method.GET, credentials, session = principalManager).status shouldBe Status.OK
                request(Method.POST, credentials, session = principalManager, body = """{"label":"x"}""").status shouldBe Status.FORBIDDEN
                request(Method.DELETE, "$credentials/$credential", session = principalManager).status shouldBe Status.FORBIDDEN
                // A service is not trusted because it is a service.
                val serviceToken = accessToken(service, createCredential(service)["secret"]!!.jsonPrimitive.content)
                request(Method.POST, credentials, bearer = serviceToken, body = """{"label":"x"}""").status shouldBe Status.FORBIDDEN
            }

            test("unknown, foreign, and user targets are not found; invalid input is rejected") {
                val service = createService("targets")
                val other = createService("other-targets")
                val credential = createCredential(service)["credentialId"]!!.jsonPrimitive.content
                val userId = user("targets-user").id

                request(Method.POST, "$admin/services/${userId.value}/credentials", session = administrator, body = """{"label":"x"}""")
                    .status shouldBe Status.NOT_FOUND
                request(Method.GET, "$admin/services/${UUID.randomUUID()}/credentials", session = administrator).status shouldBe
                    Status.NOT_FOUND
                request(Method.DELETE, "$admin/services/${other.value}/credentials/$credential", session = administrator).status shouldBe
                    Status.NOT_FOUND
                request(Method.DELETE, "$admin/services/${service.value}/credentials/${UUID.randomUUID()}", session = administrator)
                    .status shouldBe Status.NOT_FOUND
                request(Method.POST, "$admin/services/${service.value}/credentials", session = administrator, body = """{"label":" "}""")
                    .status shouldBe Status.UNPROCESSABLE_ENTITY
                request(Method.POST, "$admin/services/${service.value}/credentials", session = administrator, body = """{}""")
                    .status shouldBe Status.BAD_REQUEST
            }

            test("rotation: a new credential works before the old one is revoked, and the old one stops afterwards") {
                val service = createService("rotated")
                val first = createCredential(service, "credential A")
                val firstToken = accessToken(service, first["secret"]!!.jsonPrimitive.content)
                val second = createCredential(service, "credential B")
                accessToken(service, second["secret"]!!.jsonPrimitive.content)

                request(
                    Method.DELETE,
                    "$admin/services/${service.value}/credentials/${first["credentialId"]!!.jsonPrimitive.content}",
                    session = administrator,
                ).status shouldBe Status.NO_CONTENT

                token(service, first["secret"]!!.jsonPrimitive.content).status shouldBe Status.UNAUTHORIZED
                token(service, second["secret"]!!.jsonPrimitive.content).status shouldBe Status.OK
                // A token already issued to the revoked credential lives until it expires.
                request(Method.GET, "/app/me", bearer = firstToken).status shouldBe Status.OK
            }
        }

        context("OpenAPI") {
            test("bearer authentication, the public token endpoint, and credential schemas without secrets at rest") {
                val document = Json.parseToJsonElement(openApi(Request(Method.GET, "/openapi.json")).bodyString()).jsonObject
                val schemes = document["components"]!!.jsonObject["securitySchemes"]!!.jsonObject

                schemes["serviceAccessToken"]!!.jsonObject.let {
                    it["type"]!!.jsonPrimitive.content shouldBe "http"
                    it["scheme"]!!.jsonPrimitive.content shouldBe "bearer"
                }
                val paths = document["paths"]!!.jsonObject
                val tokenOperation = paths["/auth/service/token"]!!.jsonObject["post"]!!.jsonObject
                // Security is declared per operation, with no document-wide default to inherit: the token endpoint is public.
                document["security"] shouldBe null
                (tokenOperation["security"]?.jsonArray?.size ?: 0) shouldBe 0
                tokenOperation.toString() shouldContain "401"
                val credentialOperations = paths["$admin/services/{serviceId}/credentials"]!!.jsonObject
                listOf("get", "post").forEach { method ->
                    credentialOperations[method]!!.jsonObject["security"]!!.jsonArray.toString() shouldContain "serviceAccessToken"
                }
                paths["$admin/services/{serviceId}/credentials/{credentialId}"]!!.jsonObject.keys shouldBe setOf("delete")

                val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
                schemas.toString() shouldNotContain "argon2"
                schemas.toString() shouldNotContain "hash"
                schemas["ServiceCredentialDto"]!!.jsonObject["properties"]!!.jsonObject.keys shouldBe
                    setOf("credentialId", "serviceId", "label", "createdAt", "revoked", "revokedAt")
                ("secret" in schemas["IssuedServiceCredentialDto"]!!.jsonObject["properties"]!!.jsonObject.keys) shouldBe true
                (schemas["ServiceAccessTokenDto"]!!.jsonObject["properties"]!!.jsonObject.keys) shouldBe
                    setOf("accessToken", "tokenType", "expiresAt", "expiresIn")
            }
        }

        context("configuration") {
            test("without a signing key, credentials are available but composing service tokens fails at startup") {
                val other = TestDatabase.create()
                try {
                    lateinit var supplied: CommerceRuntimeContext
                    commerceRuntime(
                        other.runtimeConfiguration(serviceTokens = null),
                        ApplicationContributions(routes = {
                            supplied = it
                            emptyList()
                        }),
                    ).use {
                        val service = ServiceId(UUID.randomUUID())
                        supplied.authorization.createService(
                            io.github.castab.commerce.staff
                                .ServiceIdentity(service, "unconfigured", PrincipalStatus.ACTIVE, emptySet()),
                        )
                        supplied.serviceCredentials
                            .create(service, "deploy")
                            .credential.active shouldBe true
                        shouldThrow<IllegalStateException> { supplied.serviceAccessTokens }.message!! shouldContain
                            "SERVICE_TOKENS_SIGNING_KEY"
                    }
                    shouldThrow<IllegalStateException> {
                        commerceRuntime(
                            other.runtimeConfiguration(serviceTokens = null),
                            ApplicationContributions(routes = {
                                listOf(
                                    contract {
                                        routes +=
                                            serviceAuthenticationHttpCapability(it, "/token").contractRoutes
                                    },
                                )
                            }),
                        )
                    }.message!! shouldContain "SERVICE_TOKENS_SIGNING_KEY"
                } finally {
                    other.close()
                }
            }
        }
    })
