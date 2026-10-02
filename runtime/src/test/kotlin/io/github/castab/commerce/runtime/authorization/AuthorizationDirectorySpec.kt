package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.session.BearerSessionToken
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.relationExists
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionKey
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.http4k.contract.Tag
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.routing.RoutingHttpHandler
import java.util.UUID

private val customPermission = PermissionDefinition(PermissionKey("example.custom.operation"), "Custom operation", null)

private fun TestDatabase.configuration() =
    CommerceRuntimeConfiguration(
        server = CommerceRuntimeConfiguration.Server(0),
        database = configuration,
        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
    )

private fun user(name: String) = User(UserId(UUID.randomUUID()), name, null, null, name, PrincipalStatus.ACTIVE, emptySet())

private fun role(
    key: String,
    permissions: Set<PermissionKey>,
) = RoleDefinition(RoleKey(key), key, null, permissions)

class AuthorizationDirectorySpec :
    FunSpec({
        test("V4 migrates a database already at the released V3 session schema") {
            withTestDatabase { _, dataSource ->
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .schemas("commerce")
                    .table("flyway_schema_history")
                    .locations("classpath:db/commerce")
                    .target("3")
                    .load()
                    .migrate()
                dataSource.appliedVersions("commerce") shouldBe listOf("1", "2", "3")
                MigrationLifecycle(dataSource).migrate()
                dataSource.appliedVersions("commerce") shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")
                dataSource.relationExists("commerce.principal_roles") shouldBe true
            }
        }

        test("V4 schema enforces principal kinds, unique normalized usernames, assignments, and references") {
            withTestDatabase { database, dataSource ->
                commerceRuntime(database.configuration(), ApplicationContributions()).use {
                    dataSource.appliedVersions("commerce") shouldBe listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")
                    listOf("principals", "users", "service_identities", "roles", "role_permissions", "principal_roles")
                        .forEach { table -> dataSource.relationExists("commerce.$table") shouldBe true }
                    shouldThrow<Exception> {
                        dataSource.execute("INSERT INTO commerce.principals VALUES ('UNKNOWN', '${UUID.randomUUID()}', 'ACTIVE')")
                    }
                    shouldThrow<Exception> {
                        dataSource.execute("INSERT INTO commerce.principal_roles VALUES ('USER', '${UUID.randomUUID()}', 'missing')")
                    }
                }
            }
        }

        test("principals, roles, assignments, and custom permissions round trip with live resolution") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                commerceRuntime(
                    database.configuration(),
                    ApplicationContributions(
                        permissionDefinitions = listOf(customPermission),
                        routes = {
                            context = it
                            emptyList()
                        },
                    ),
                ).use {
                    val auth = context.authorization
                    val staff = user(" Brayan ")
                    val stored = auth.createUser(staff)
                    stored.username shouldBe "Brayan"
                    auth.findUserByUsername("BRAYAN") shouldBe stored
                    shouldThrow<CommerceFailure.Conflict> { auth.createUser(user("brayan")) }
                    val updated = User(stored.id, "Brayan", "Brayan", "Castab", "Brayan C", PrincipalStatus.ACTIVE, emptySet())
                    auth.updateUserProfile(updated.id, updated.username, updated.firstName, updated.lastName, updated.displayName) shouldBe
                        updated
                    auth.listUsers().size shouldBe 1
                    val service = ServiceIdentity(ServiceId(UUID.randomUUID()), "worker", PrincipalStatus.ACTIVE, emptySet())
                    auth.createService(service)
                    auth.getService(service.id) shouldBe service
                    auth.renameService(service.id, "batch-worker").name shouldBe "batch-worker"
                    val serviceToken = context.sessions.create(service.id).token
                    val conventional = role(CommerceRoles.Administrator.value, emptySet())
                    auth.createRole(conventional)
                    auth.assignRole(stored.id, conventional.key)
                    auth.permissionResolver.permissionsFor(stored.id) shouldBe emptySet()
                    val grant = role("example.operator", setOf(customPermission.key))
                    auth.createRole(grant)
                    auth.getRole(grant.key) shouldBe grant
                    auth.assignRole(stored.id, grant.key)
                    auth.assignRole(stored.id, grant.key)
                    auth.assignedRoles(stored.id).size shouldBe 2
                    auth.permissionResolver.permissionsFor(stored.id) shouldBe setOf(customPermission.key)
                    auth.permissionCatalog.find(customPermission.key) shouldBe customPermission
                    auth.replaceRolePermissions(grant.key, emptySet())
                    auth.permissionResolver.permissionsFor(stored.id) shouldBe emptySet()
                    auth.replaceRolePermissions(grant.key, setOf(customPermission.key))
                    auth.permissionResolver.permissionsFor(stored.id) shouldBe setOf(customPermission.key)
                    auth.unassignRole(stored.id, grant.key)
                    auth.permissionResolver.permissionsFor(stored.id) shouldBe emptySet()
                    auth.assignRole(service.id, grant.key)
                    auth.permissionResolver.permissionsFor(service.id) shouldBe setOf(customPermission.key)
                    auth.setStatus(service.id, PrincipalStatus.DISABLED)
                    auth.permissionResolver.permissionsFor(service.id) shouldBe emptySet()
                    context.sessions.resolve(serviceToken).shouldBeNull()
                    shouldThrow<CommerceFailure.Conflict> { auth.deleteRole(grant.key) }
                    auth.unassignRole(service.id, grant.key)
                    auth.deleteRole(grant.key)
                    auth.getRole(grant.key).shouldBeNull()
                    auth.unassignRole(stored.id, conventional.key)
                    auth.deleteRole(conventional.key)
                    shouldThrow<CommerceFailure.NotFound> { auth.assignRole(stored.id, grant.key) }
                    shouldThrow<CommerceFailure.ValidationFailed> {
                        auth.createRole(
                            role("bad", setOf(PermissionKey("invented.permission"))),
                        )
                    }
                }
            } finally {
                database.close()
            }
        }

        test("disable and session revocation share the caller transaction with application data") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                commerceRuntime(
                    database.configuration(),
                    ApplicationContributions(
                        migrations = testApplicationMigrations("classpath:db/testapp"),
                        routes = {
                            context = it
                            emptyList()
                        },
                    ),
                ).use {
                    val auth = context.authorization
                    val staff = auth.createUser(user("staff"))
                    val token = context.sessions.create(staff.id).token
                    shouldThrow<IllegalStateException> {
                        context.transactor.inTransaction { tx ->
                            auth.setStatus(tx, staff.id, PrincipalStatus.DISABLED)
                            tx.handle
                                .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, 'credential')")
                                .bind("id", UUID.randomUUID())
                                .execute()
                            error("roll back")
                        }
                    }
                    auth.getUser(staff.id)!!.status shouldBe PrincipalStatus.ACTIVE
                    context.sessions.resolve(token) shouldBe staff.id
                    context.transactor.inTransaction { tx ->
                        auth.setStatus(tx, staff.id, PrincipalStatus.DISABLED)
                        tx.handle
                            .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, 'credential')")
                            .bind("id", UUID.randomUUID())
                            .execute()
                    }
                    auth.getUser(staff.id)!!.status shouldBe PrincipalStatus.DISABLED
                    context.sessions.resolve(token).shouldBeNull()
                    auth.setStatus(staff.id, PrincipalStatus.ACTIVE)
                    context.sessions.resolve(token).shouldBeNull()
                    context.sessions.resolve(context.sessions.create(staff.id).token) shouldBe staff.id
                }
            } finally {
                database.close()
            }
        }

        test("application bootstrap joins user, role, assignment, and credential writes") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                commerceRuntime(
                    database.configuration(),
                    ApplicationContributions(
                        migrations = testApplicationMigrations("classpath:db/testapp"),
                        routes = {
                            context = it
                            emptyList()
                        },
                    ),
                ).use {
                    val initial = user("bootstrap")
                    val admin = role(CommerceRoles.Administrator.value, setOf(CommercePermissions.PrincipalManage))
                    context.transactor.inTransaction { tx ->
                        context.authorization.createRole(tx, admin)
                        context.authorization.createUser(tx, initial)
                        tx.handle
                            .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, 'hash')")
                            .bind("id", initial.id.value)
                            .execute()
                        context.authorization.assignRole(tx, initial.id, admin.key)
                    }
                    context.authorization.permissionResolver.permissionsFor(initial.id) shouldBe setOf(CommercePermissions.PrincipalManage)
                    context.transactor.inTransaction { tx ->
                        tx.handle
                            .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                            .bind("id", initial.id.value)
                            .mapTo(String::class.java)
                            .one() shouldBe "hash"
                    }
                }
            } finally {
                database.close()
            }
        }

        test("HTTP permission families, catalog, live grants, disablement, and OpenAPI") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                lateinit var host: RoutingHttpHandler
                val runtime =
                    commerceRuntime(
                        database.configuration(),
                        ApplicationContributions(
                            permissionDefinitions = listOf(customPermission),
                            routes = { supplied ->
                                context = supplied
                                val access =
                                    AccessControl(
                                        sessionAuthentication(supplied.sessions, BearerSessionToken),
                                        supplied.authorization.permissionResolver,
                                    )
                                host =
                                    contract {
                                        renderer = OpenApi3(ApiInfo("Authorization", "1"), Jackson)
                                        descriptionPath = "/openapi.json"
                                        routes +=
                                            authorizationAdministrationHttpCapability(
                                                supplied,
                                                access,
                                                "/admin/access",
                                                setOf(Tag("Staff administration", "Principals and roles")),
                                            ).contractRoutes
                                    }
                                listOf(host)
                            },
                        ),
                    )
                runtime.use {
                    val auth = context.authorization
                    val administrator = auth.createUser(user("admin"))
                    val viewer = auth.createUser(user("viewer"))
                    val adminRole =
                        role(
                            "example.admin",
                            setOf(
                                CommercePermissions.PrincipalRead,
                                CommercePermissions.PrincipalManage,
                                CommercePermissions.RoleRead,
                                CommercePermissions.RoleManage,
                                CommercePermissions.RoleAssign,
                            ),
                        )
                    auth.createRole(adminRole)
                    auth.assignRole(administrator.id, adminRole.key)
                    val adminToken =
                        context.sessions
                            .create(administrator.id)
                            .token.value
                    val viewerToken =
                        context.sessions
                            .create(viewer.id)
                            .token.value

                    fun request(
                        method: Method,
                        path: String,
                        token: String? = null,
                        body: String? = null,
                    ) = runtime.http(
                        Request(method, path)
                            .let { if (token == null) it else it.header("Authorization", "Bearer $token") }
                            .let { if (body == null) it else it.header("Content-Type", "application/json").body(body) },
                    )
                    val base = "/admin/access"
                    val checks =
                        listOf(
                            Method.GET to "$base/users",
                            Method.POST to "$base/users",
                            Method.GET to "$base/services",
                            Method.POST to "$base/services",
                            Method.GET to "$base/roles",
                            Method.POST to "$base/roles",
                            Method.PUT to "$base/users/${viewer.id.value}/roles/${adminRole.key.value}",
                        )
                    checks.forEach { (method, path) ->
                        request(method, path).status shouldBe Status.UNAUTHORIZED
                        request(method, path, viewerToken).status shouldBe Status.FORBIDDEN
                    }
                    request(Method.GET, "$base/users", adminToken).status shouldBe Status.OK
                    request(Method.GET, "$base/services", adminToken).status shouldBe Status.OK
                    request(Method.GET, "$base/permissions", adminToken).bodyString().contains(customPermission.key.value) shouldBe true
                    request(
                        Method.POST,
                        "$base/roles",
                        adminToken,
                        """{"key":"example.custom","displayName":"Custom","description":"Custom grants","permissions":["example.custom.operation"]}""",
                    ).status shouldBe Status.CREATED
                    request(Method.GET, "$base/roles/example.custom", adminToken).status shouldBe Status.OK
                    request(
                        Method.PUT,
                        "$base/roles/example.custom/permissions",
                        adminToken,
                        """{"permissions":["commerce.principal.read"]}""",
                    ).status shouldBe Status.OK
                    auth.getRole(RoleKey("example.custom"))!!.permissions shouldBe setOf(CommercePermissions.PrincipalRead)
                    request(Method.POST, "$base/services", adminToken, """{"name":"worker"}""").status shouldBe Status.CREATED
                    auth.listServices().size shouldBe 1
                    request(
                        Method.POST,
                        "$base/roles",
                        adminToken,
                        """{"key":"bad","displayName":"Bad","description":null,"permissions":["unknown"]}""",
                    ).status shouldBe
                        Status.UNPROCESSABLE_ENTITY
                    request(Method.PUT, "$base/users/${viewer.id.value}/roles/${adminRole.key.value}", adminToken).status shouldBe
                        Status.NO_CONTENT
                    request(Method.PUT, "$base/users/${viewer.id.value}/roles/${adminRole.key.value}", adminToken).status shouldBe
                        Status.NO_CONTENT
                    auth.assignedRoles(viewer.id).size shouldBe 1
                    request(Method.GET, "$base/users", viewerToken).status shouldBe Status.OK
                    request(Method.DELETE, "$base/roles/${adminRole.key.value}", adminToken).status shouldBe Status.CONFLICT
                    val spec = host(Request(Method.GET, "/openapi.json")).bodyString()
                    spec.contains("401") shouldBe true
                    spec.contains("403") shouldBe true
                    val document = Json.parseToJsonElement(spec).jsonObject
                    val operations =
                        document["paths"]!!
                            .jsonObject
                            .filterKeys { it.startsWith("$base/") }
                            .values
                            .flatMap { it.jsonObject.values }
                    operations.size shouldBe 26
                    operations.forEach { operation ->
                        operation.jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
                            listOf("Staff administration")
                    }
                    document["tags"]!!
                        .jsonArray
                        .single()
                        .jsonObject["description"]!!
                        .jsonPrimitive.content shouldBe "Principals and roles"
                    val blankTagAccess = AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), auth.permissionResolver)
                    listOf("", " ").forEach { name ->
                        shouldThrow<IllegalArgumentException> {
                            authorizationAdministrationHttpCapability(context, blankTagAccess, "/admin/other", setOf(Tag(name)))
                        }
                    }
                    request(Method.PUT, "$base/users/${viewer.id.value}/status", adminToken, """{"status":"DISABLED"}""").status shouldBe
                        Status.OK
                    request(Method.GET, "$base/users", viewerToken).status shouldBe Status.UNAUTHORIZED
                }
            } finally {
                database.close()
            }
        }

        test("duplicate application permission keys fail composition") {
            val database = TestDatabase.create()
            try {
                shouldThrow<IllegalArgumentException> {
                    commerceRuntime(
                        database.configuration(),
                        ApplicationContributions(
                            permissionDefinitions = listOf(PermissionDefinition(CommercePermissions.PrincipalRead, "Duplicate", null)),
                        ),
                    )
                }
            } finally {
                database.close()
            }
        }

        test("stored grants must remain in the running permission catalog") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                val withCustomPermission =
                    ApplicationContributions(
                        permissionDefinitions = listOf(customPermission),
                        routes = {
                            context = it
                            emptyList()
                        },
                    )
                val operator = user("operator")
                commerceRuntime(database.configuration(), withCustomPermission).use {
                    context.authorization.createUser(operator)
                    context.authorization.createRole(role("example.operator", setOf(customPermission.key)))
                    context.authorization.assignRole(operator.id, RoleKey("example.operator"))
                }
                commerceRuntime(database.configuration(), withCustomPermission).use {
                    context.authorization.permissionResolver.permissionsFor(operator.id) shouldBe setOf(customPermission.key)
                }
                val failure =
                    shouldThrow<IllegalStateException> {
                        commerceRuntime(database.configuration(), ApplicationContributions())
                    }
                failure.message!!.contains(customPermission.key.value) shouldBe true

                commerceRuntime(
                    database.configuration(),
                    ApplicationContributions(
                        migrations = testApplicationMigrations("classpath:db/testapp-remove-stale-permission"),
                        routes = {
                            context = it
                            emptyList()
                        },
                    ),
                ).use {
                    context.authorization.permissionResolver.permissionsFor(operator.id) shouldBe emptySet()
                    context.authorization.getRole(RoleKey("example.operator"))!!.permissions shouldBe emptySet()
                }
            } finally {
                database.close()
            }
        }

        test("a grant inserted after startup fails closed during live permission resolution") {
            val database = TestDatabase.create()
            try {
                lateinit var context: CommerceRuntimeContext
                commerceRuntime(
                    database.configuration(),
                    ApplicationContributions(routes = {
                        context = it
                        emptyList()
                    }),
                ).use {
                    val staff = context.authorization.createUser(user("operator"))
                    val key = RoleKey("example.operator")
                    context.authorization.createRole(role(key.value, emptySet()))
                    context.authorization.assignRole(staff.id, key)
                    context.transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO commerce.role_permissions (role_key, permission_key) VALUES (:role, :permission)",
                            ).bind("role", key.value)
                            .bind("permission", "unknown.permission")
                            .execute()
                    }
                    shouldThrow<IllegalStateException> {
                        context.authorization.permissionResolver.permissionsFor(staff.id)
                    }
                }
            } finally {
                database.close()
            }
        }
    })
