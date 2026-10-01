package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.offering.OfferingsHttpAccess
import io.github.castab.commerce.runtime.offering.OfferingsHttpBinding
import io.github.castab.commerce.runtime.offering.offeringsHttpCapability
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionGroup
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.contract.Tag
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.format.Jackson
import org.http4k.routing.bind
import java.util.UUID

private val inquiries = PermissionGroup("catering.inquiries")
private val applicationPermissions =
    listOf(
        PermissionDefinition(PermissionKey("catering.inquiries.assign"), "Assign inquiries", "Assign inquiries to staff.", inquiries),
        PermissionDefinition(PermissionKey("catering.inquiries.read"), "Read inquiries", "View catering inquiries.", inquiries),
        PermissionDefinition(
            PermissionKey("events.calendar.manage"),
            "Manage the event calendar",
            "Create and move calendar events.",
            PermissionGroup("events"),
        ),
    )

private class Host(
    val runtime: CommerceRuntime,
    val context: CommerceRuntimeContext,
) {
    fun get(
        path: String,
        token: String? = null,
    ): Response = runtime.http(Request(Method.GET, path).let { if (token == null) it else it.header("Authorization", "Bearer $token") })

    fun user(
        name: String,
        permissions: Set<PermissionKey> = emptySet(),
    ): Pair<User, String> {
        val user =
            context.authorization.createUser(
                User(UserId(UUID.randomUUID()), name, null, null, "$name display", PrincipalStatus.ACTIVE, emptySet()),
            )
        grant(user.id, "example.$name", permissions)
        return user to
            context.sessions
                .create(user.id)
                .token.value
    }

    fun grant(
        id: PrincipalId,
        role: String,
        permissions: Set<PermissionKey> = emptySet(),
    ) {
        if (permissions.isEmpty()) return
        context.authorization.createRole(RoleDefinition(RoleKey(role), role, null, permissions))
        context.authorization.assignRole(id, RoleKey(role))
    }
}

private fun withHost(
    mount: Boolean = true,
    block: (Host) -> Unit,
) {
    val database = TestDatabase.create()
    try {
        lateinit var context: CommerceRuntimeContext
        val configuration =
            CommerceRuntimeConfiguration(
                server = CommerceRuntimeConfiguration.Server(0),
                database = database.configuration,
                migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
            )
        val runtime =
            commerceRuntime(
                configuration,
                ApplicationContributions(
                    permissionDefinitions = applicationPermissions,
                    routes = { supplied ->
                        context = supplied
                        val access =
                            AccessControl(
                                sessionAuthentication(supplied.sessions, BearerSessionToken),
                                supplied.authorization,
                            )
                        val tags = setOf(Tag("Access"))
                        if (!mount) {
                            emptyList()
                        } else {
                            listOf(
                                contract {
                                    renderer = OpenApi3(ApiInfo("Host", "1"), Jackson)
                                    descriptionPath = "/openapi.json"
                                    routes +=
                                        permissionCatalogHttpCapability(access, "/authorization", tags).contractRoutes
                                    routes += currentPrincipalHttpCapability(access, "/me", tags).contractRoutes
                                    routes += authorizationAdministrationHttpCapability(supplied, access, "/admin/access").contractRoutes
                                },
                            )
                        }
                    },
                ),
            )
        runtime.use { block(Host(it, context)) }
    } finally {
        database.close()
    }
}

private fun Response.json(): JsonObject = Json.parseToJsonElement(bodyString()).jsonObject

class AuthorizationReadHttpSpec :
    FunSpec({
        test("the catalog requires RoleRead and returns every definition with complete metadata in key order") {
            withHost { host ->
                val catalog = host.context.authorization.permissionCatalog
                val (_, reader) = host.user("reader", setOf(CommercePermissions.RoleRead))
                val (_, manager) = host.user("manager", setOf(CommercePermissions.RoleManage, CommercePermissions.RoleAssign))
                val (_, nobody) = host.user("nobody")

                host.get("/authorization/permissions").status shouldBe Status.UNAUTHORIZED
                host.get("/authorization/permissions", "not-a-token").status shouldBe Status.UNAUTHORIZED
                host.get("/authorization/permissions", nobody).status shouldBe Status.FORBIDDEN
                host.get("/authorization/permissions", manager).status shouldBe Status.FORBIDDEN

                val response = host.get("/authorization/permissions", reader)
                response.status shouldBe Status.OK
                val body = Json.decodeFromString(PermissionsDto.serializer(), response.bodyString())
                body.revision shouldBe catalog.revision
                body.permissions.size shouldBe 16
                body.permissions.map { it.key } shouldBe body.permissions.map { it.key }.sorted()
                body.permissions shouldContainExactly
                    catalog.definitions.map { PermissionDto(it.key.value, it.group.value, it.displayName, it.description) }
                body.permissions.first() shouldBe
                    PermissionDto("catering.inquiries.assign", "catering.inquiries", "Assign inquiries", "Assign inquiries to staff.")
                body.permissions.single { it.key == "commerce.role.read" } shouldBe
                    PermissionDto(
                        "commerce.role.read",
                        "commerce.roles",
                        "Read roles and permissions",
                        "View role definitions and the permission catalog.",
                    )
                response.json().keys shouldBe setOf("revision", "permissions")
                response.json()["permissions"]!!.jsonArray.forEach {
                    it.jsonObject.keys shouldBe setOf("key", "group", "displayName", "description")
                }
                host.get("/authorization/permissions", reader).bodyString() shouldBe response.bodyString()
            }
        }

        test("a service principal with RoleRead can read the catalog") {
            withHost { host ->
                val service =
                    host.context.authorization.createService(
                        ServiceIdentity(ServiceId(UUID.randomUUID()), "ui-sync", PrincipalStatus.ACTIVE, emptySet()),
                    )
                host.grant(service.id, "example.ui-sync", setOf(CommercePermissions.RoleRead))
                val token =
                    host.context.sessions
                        .create(service.id)
                        .token.value

                host.get("/authorization/permissions", token).status shouldBe Status.OK
            }
        }

        test("the routes exist only where the application mounts them") {
            withHost(mount = false) { host ->
                val (_, reader) = host.user("reader", setOf(CommercePermissions.RoleRead))

                host.get("/authorization/permissions", reader).status shouldBe Status.NOT_FOUND
                host.get("/me", reader).status shouldBe Status.NOT_FOUND
            }
        }

        test("the current principal receives its resolved effective permissions, live and without role names") {
            withHost { host ->
                val catalog = host.context.authorization.permissionCatalog
                val (user, token) =
                    host.user(
                        "staff",
                        setOf(CommercePermissions.RoleRead, PermissionKey("catering.inquiries.assign"), CommercePermissions.PrincipalRead),
                    )
                host.grant(user.id, "example.overlap", setOf(CommercePermissions.RoleRead, PermissionKey("events.calendar.manage")))

                host.get("/me").status shouldBe Status.UNAUTHORIZED
                host.get("/me", "not-a-token").status shouldBe Status.UNAUTHORIZED

                val response = host.get("/me", token)
                response.status shouldBe Status.OK
                response.json().keys shouldBe setOf("principal", "permissions", "permissionCatalogRevision")
                Json.decodeFromString(CurrentPrincipalDto.serializer(), response.bodyString()) shouldBe
                    CurrentPrincipalDto(
                        PrincipalSummaryDto("USER", user.id.value.toString(), "staff display"),
                        listOf("catering.inquiries.assign", "commerce.principal.read", "commerce.role.read", "events.calendar.manage"),
                        catalog.revision,
                    )

                host.context.authorization.replaceRolePermissions(RoleKey("example.overlap"), emptySet())
                host.context.authorization.unassignRole(user.id, RoleKey("example.staff"))
                val emptied = host.get("/me", token)
                emptied.status shouldBe Status.OK
                emptied.json()["permissions"] shouldBe JsonArray(emptyList())

                host.context.authorization.setStatus(user.id, PrincipalStatus.DISABLED)
                host.get("/me", token).status shouldBe Status.UNAUTHORIZED
            }
        }

        test("a principal without grants receives an empty list, and services are described by name") {
            withHost { host ->
                val (_, token) = host.user("newcomer")
                host.get("/me", token).json()["permissions"] shouldBe JsonArray(emptyList())

                val service =
                    host.context.authorization.createService(
                        ServiceIdentity(ServiceId(UUID.randomUUID()), "reporter", PrincipalStatus.ACTIVE, emptySet()),
                    )
                host.grant(service.id, "example.reporter", setOf(CommercePermissions.PaymentRecord))
                val current =
                    Json.decodeFromString(
                        CurrentPrincipalDto.serializer(),
                        host
                            .get(
                                "/me",
                                host.context.sessions
                                    .create(service.id)
                                    .token.value,
                            ).bodyString(),
                    )
                current.principal shouldBe PrincipalSummaryDto("SERVICE", service.id.value.toString(), "reporter")
                current.permissions shouldBe listOf("commerce.payment.record")
            }
        }

        test("OpenAPI documents strict, required response shapes that match the responses") {
            withHost { host ->
                val (_, token) = host.user("reader", setOf(CommercePermissions.RoleRead))
                val document = host.get("/openapi.json").json()
                val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject

                fun resolve(schema: JsonElement): JsonObject {
                    val node = schema.jsonObject
                    val ref = node["\$ref"]?.jsonPrimitive?.content ?: return node
                    return schemas[ref.removePrefix("#/components/schemas/")]!!.jsonObject
                }

                // Every property of every object is required and non-nullable, and the documented
                // properties are exactly those the live response carries.
                fun assertMatches(
                    schema: JsonElement,
                    value: JsonElement,
                ) {
                    val resolved = resolve(schema)
                    when (value) {
                        is JsonObject -> {
                            val properties = resolved["properties"]!!.jsonObject
                            properties.keys shouldBe value.keys
                            resolved["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet() shouldBe value.keys
                            properties.values.forEach { (it.jsonObject["nullable"]?.jsonPrimitive?.content ?: "false") shouldBe "false" }
                            value.forEach { (name, nested) -> assertMatches(properties[name]!!, nested) }
                        }
                        is JsonArray -> {
                            resolved["type"]!!.jsonPrimitive.content shouldBe "array"
                            value.forEach { assertMatches(resolved["items"]!!, it) }
                        }
                        else -> resolved["type"]!!.jsonPrimitive.content shouldBe "string"
                    }
                }

                val paths = document["paths"]!!.jsonObject

                fun operation(path: String) = paths[path]!!.jsonObject["get"]!!.jsonObject

                fun okSchema(path: String) =
                    operation(path)["responses"]!!
                        .jsonObject["200"]!!
                        .jsonObject["content"]!!
                        .jsonObject["application/json"]!!
                        .jsonObject["schema"]!!

                assertMatches(okSchema("/authorization/permissions"), host.get("/authorization/permissions", token).json())
                assertMatches(okSchema("/me"), host.get("/me", token).json())
                operation("/authorization/permissions")["responses"]!!.jsonObject.keys shouldBe setOf("200", "401", "403")
                operation("/me")["responses"]!!.jsonObject.keys shouldBe setOf("200", "401")
                listOf("/authorization/permissions", "/me").forEach { path ->
                    operation(path)["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Access")
                }
                operation("/authorization/permissions")["operationId"]!!.jsonPrimitive.content shouldBe "authorizationListPermissions"
                operation("/me")["operationId"]!!.jsonPrimitive.content shouldBe "authorizationCurrentPrincipal"
            }
        }

        test("capabilities reject malformed paths and blank tags") {
            withHost { host ->
                val access =
                    AccessControl(
                        sessionAuthentication(host.context.sessions, BearerSessionToken),
                        host.context.authorization,
                    )
                listOf("", "/", "authorization", "/authorization/", "/a//b", "/{id}", "/a?b").forEach { path ->
                    shouldThrow<IllegalArgumentException> { permissionCatalogHttpCapability(access, path) }
                    shouldThrow<IllegalArgumentException> { currentPrincipalHttpCapability(access, path) }
                }
                shouldThrow<IllegalArgumentException> {
                    permissionCatalogHttpCapability(
                        access,
                        "/authorization",
                        setOf(Tag(" ")),
                    )
                }
                shouldThrow<IllegalArgumentException> { currentPrincipalHttpCapability(access, "/me", setOf(Tag(""))) }
            }
        }

        test("the standalone and administration catalog routes serve one identical representation of the running catalog") {
            withHost { host ->
                val (_, reader) = host.user("reader", setOf(CommercePermissions.RoleRead))
                val (_, nobody) = host.user("nobody")
                val standalone = host.get("/authorization/permissions", reader)
                val administration = host.get("/admin/access/permissions", reader)

                standalone.status shouldBe Status.OK
                administration.bodyString() shouldBe standalone.bodyString()
                Json.decodeFromString(PermissionsDto.serializer(), standalone.bodyString()) shouldBe
                    host.context.authorization.permissionCatalog
                        .dto()
                host.get("/admin/access/permissions").status shouldBe Status.UNAUTHORIZED
                host.get("/admin/access/permissions", nobody).status shouldBe Status.FORBIDDEN
                host
                    .get("/me", reader)
                    .json()["permissionCatalogRevision"]!!
                    .jsonPrimitive.content shouldBe
                    standalone.json()["revision"]!!.jsonPrimitive.content
            }
        }

        test("a capability cannot be wired to authorization state other than the runtime's") {
            withHost { host ->
                val foreign =
                    AccessControl(
                        sessionAuthentication(host.context.sessions, BearerSessionToken),
                        PermissionCatalog(commercePermissionDefinitions),
                        host.context.authorization.permissionResolver,
                    )

                shouldThrow<IllegalArgumentException> {
                    authorizationAdministrationHttpCapability(host.context, foreign, "/admin/other")
                }.message shouldBe "AccessControl must be built from this runtime's authorization directory"
            }
        }

        test("every permission declared by the runtime's own routes is in a runtime-only catalog") {
            // No application permissions: composing each runtime capability declares every
            // permission its routes enforce, and AccessControl rejects any key the catalog lacks.
            val database = TestDatabase.create()
            try {
                val configuration =
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(0),
                        database = database.configuration,
                        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
                    )
                var composed = 0
                commerceRuntime(
                    configuration,
                    ApplicationContributions(routes = { context ->
                        val access = AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), context.authorization)
                        context.authorization.permissionCatalog.definitions shouldBe commercePermissionDefinitions.sortedBy { it.key.value }
                        val routes =
                            authorizationAdministrationHttpCapability(context, access, "/admin").contractRoutes +
                                permissionCatalogHttpCapability(access, "/authorization").contractRoutes +
                                currentPrincipalHttpCapability(access, "/me").contractRoutes +
                                offeringsHttpCapability(
                                    context,
                                    OfferingsHttpBinding(
                                        OfferingsCatalogId(UUID.randomUUID()),
                                        "/offerings",
                                        "runtimeCatalog",
                                        OfferingsHttpAccess.ReadWrite(access),
                                    ),
                                ).contractRoutes
                        composed = routes.size
                        listOf(contract { this.routes += routes })
                    }),
                ).close()
                (composed > 0) shouldBe true
            } finally {
                database.close()
            }
        }

        test("a route declaring an unregistered permission fails runtime composition") {
            val database = TestDatabase.create()
            try {
                val configuration =
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(0),
                        database = database.configuration,
                        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
                    )

                fun compose(permission: PermissionKey) =
                    commerceRuntime(
                        configuration,
                        ApplicationContributions(
                            permissionDefinitions = applicationPermissions,
                            routes = { context ->
                                val access =
                                    AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), context.authorization)
                                listOf("/guarded" bind Method.GET to access.requirePermission(permission).then { Response(Status.OK) })
                            },
                        ),
                    )

                shouldThrow<IllegalArgumentException> { compose(PermissionKey("commerce.principal.reed")) }.message shouldBe
                    "Permission is not registered in the running PermissionCatalog: commerce.principal.reed"
                shouldThrow<IllegalArgumentException> { compose(PermissionKey("catering.inquries.read")) }.message shouldBe
                    "Permission is not registered in the running PermissionCatalog: catering.inquries.read"
                compose(PermissionKey("catering.inquiries.read")).close()
                compose(CommercePermissions.PrincipalRead).close()
            } finally {
                database.close()
            }
        }

        test("administration role reads never describe a stored grant outside the catalog") {
            withHost { host ->
                val (_, admin) = host.user("admin", setOf(CommercePermissions.RoleRead, CommercePermissions.RoleManage))
                val manager = RoleKey("example.manager")
                host.context.authorization.createRole(RoleDefinition(manager, "Manager", null, setOf(CommercePermissions.PrincipalRead)))

                fun send(
                    method: Method,
                    path: String,
                    body: String? = null,
                ) = host.runtime.http(
                    Request(method, path)
                        .header("Authorization", "Bearer $admin")
                        .let { if (body == null) it else it.header("Content-Type", "application/json").body(body) },
                )

                listOf("mystery.permission", "Legacy.Key").forEach { stored ->
                    host.context.transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate("INSERT INTO commerce.role_permissions (role_key, permission_key) VALUES (:role, :permission)")
                            .bind("role", manager.value)
                            .bind("permission", stored)
                            .execute()
                    }

                    listOf(
                        send(Method.GET, "/admin/access/roles/${manager.value}"),
                        send(Method.GET, "/admin/access/roles"),
                        send(Method.PATCH, "/admin/access/roles/${manager.value}", """{"displayName":"Renamed","description":null}"""),
                    ).forEach { response ->
                        response.status shouldBe Status.INTERNAL_SERVER_ERROR
                        response.json()["code"]!!.jsonPrimitive.content shouldBe "internal_failure"
                        response.bodyString().contains(stored) shouldBe false
                    }

                    val repaired =
                        send(
                            Method.PUT,
                            "/admin/access/roles/${manager.value}/permissions",
                            """{"permissions":["commerce.principal.read"]}""",
                        )
                    repaired.status shouldBe Status.OK
                    repaired.bodyString().contains(stored) shouldBe false
                    send(Method.GET, "/admin/access/roles/${manager.value}").json()["displayName"]!!.jsonPrimitive.content shouldBe
                        "Manager"
                    send(Method.GET, "/admin/access/roles").status shouldBe Status.OK
                }
            }
        }

        test("an unknown grant stored after startup fails enforcement and the current principal closed") {
            withHost { host ->
                val (user, token) = host.user("reader", setOf(CommercePermissions.RoleRead))
                host.get("/authorization/permissions", token).status shouldBe Status.OK
                host.context.transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate(
                            "INSERT INTO commerce.role_permissions (role_key, permission_key) VALUES ('example.reader', 'Legacy.Key')",
                        ).execute()
                }

                host.get("/authorization/permissions", token).status shouldBe Status.INTERNAL_SERVER_ERROR
                val reported = host.get("/me", token)
                reported.status shouldBe Status.INTERNAL_SERVER_ERROR
                reported.bodyString().contains("Legacy.Key") shouldBe false
                shouldThrow<IllegalStateException> {
                    host.context.authorization.permissionResolver.permissionsFor(
                        user.id,
                    )
                }.message shouldBe
                    "Stored role permissions missing from PermissionCatalog: Legacy.Key"
            }
        }
    })
