package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Path
import java.util.UUID

/** Contract routes that a host mounts in its own http4k/OpenAPI contract. */
class AuthorizationAdministrationHttpCapability internal constructor(
    val contractRoutes: List<ContractRoute>,
)

/**
 * Runtime-owned principal/RBAC administration. The host chooses placement and supplies
 * authentication through [accessControl]; each route declares its own commerce permission.
 * No credential, cookie, login, or bootstrap policy is included.
 */
fun authorizationAdministrationHttpCapability(
    context: CommerceRuntimeContext,
    accessControl: AccessControl,
    basePath: String,
): AuthorizationAdministrationHttpCapability {
    require(
        basePath.startsWith('/') &&
            basePath.length > 1 &&
            !basePath.endsWith('/') &&
            basePath.split('/').drop(1).all { it.isNotBlank() } &&
            basePath.none { it in "?{}#" },
    ) { "Invalid administration base path" }
    val directory = context.authorization
    val userPath = Path.of("userId")
    val servicePath = Path.of("serviceId")
    val rolePath = Path.of("roleKey")
    val userBody = jsonBody(UserDto.serializer())
    val usersBody = jsonBody(UsersDto.serializer())
    val userWrite = jsonBody(UserWriteDto.serializer())
    val statusBody = jsonBody(StatusDto.serializer())
    val serviceBody = jsonBody(ServiceDto.serializer())
    val servicesBody = jsonBody(ServicesDto.serializer())
    val serviceWrite = jsonBody(ServiceWriteDto.serializer())
    val roleBody = jsonBody(RoleDto.serializer())
    val rolesBody = jsonBody(RolesDto.serializer())
    val roleWrite = jsonBody(RoleWriteDto.serializer())
    val roleProfile = jsonBody(RoleProfileDto.serializer())
    val permissionKeys = jsonBody(PermissionKeysDto.serializer())
    val permissionsBody = jsonBody(PermissionsDto.serializer())
    val assignmentsBody = jsonBody(AssignmentsDto.serializer())
    val errorBody = jsonBody(ErrorResponse.serializer())
    val sampleUser = UserDto(UUID(0, 1).toString(), "staff", "Staff", "Member", "Staff Member", "ACTIVE", listOf("commerce.manager"))
    val sampleService = ServiceDto(UUID(0, 2).toString(), "worker", "ACTIVE", listOf("commerce.manager"))
    val sampleRole = RoleDto("commerce.manager", "Manager", "Manages staff", listOf(CommercePermissions.UserRead.value))
    val samplePermission = PermissionDto(CommercePermissions.UserRead.value, "Read principals", "Read principal identities")

    fun RouteMetaDsl.errors(vararg statuses: Status) {
        statuses.forEach { status ->
            val category = ErrorCategory.entries.first { it.status == status }
            returning(status, errorBody to ErrorResponse(category.code, "Request failed"))
        }
    }

    fun RouteMetaDsl.protected(
        id: String,
        title: String,
    ) {
        operationId = "authorization$id"
        summary = title
        errors(Status.UNAUTHORIZED, Status.FORBIDDEN, Status.BAD_REQUEST, Status.UNPROCESSABLE_ENTITY, Status.NOT_FOUND, Status.CONFLICT)
    }

    fun guard(permission: PermissionKey) = accessControl.requirePermission(permission)
    val readUser = guard(CommercePermissions.UserRead)
    val manageUser = guard(CommercePermissions.UserManage)
    val readRole = guard(CommercePermissions.RoleRead)
    val manageRole = guard(CommercePermissions.RoleManage)
    val assignRole = guard(CommercePermissions.RoleAssign)

    val routes = mutableListOf<ContractRoute>()
    routes += "$basePath/users" meta {
        protected("ListUsers", "List users")
        returning(Status.OK, usersBody to UsersDto(listOf(sampleUser)))
    } bindContract Method.GET to
        readUser.then { _: Request ->
            Response(Status.OK).with(usersBody of UsersDto(directory.listUsers().map { it.dto() }))
        }
    routes += "$basePath/users" meta {
        protected("CreateUser", "Create a user without credentials")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(userWrite to UserWriteDto("staff", "Staff", "Member", "Staff Member"))
        returning(Status.CREATED, userBody to sampleUser)
    } bindContract Method.POST to
        manageUser.then { request: Request ->
            val created = directory.createUser(userWrite(request).user(UserId(UUID.randomUUID())))
            Response(Status.CREATED).with(userBody of created.dto())
        }
    routes += "$basePath/users" / userPath meta {
        protected("GetUser", "Get a user")
        returning(Status.OK, userBody to sampleUser)
    } bindContract Method.GET to { id: String ->
        readUser.then { _: Request ->
            val user = directory.getUser(id.userId()) ?: throw CommerceFailure.NotFound("User does not exist")
            Response(Status.OK).with(userBody of user.dto())
        }
    }
    routes += "$basePath/users" / userPath meta {
        protected("UpdateUser", "Update user profile fields")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(userWrite to UserWriteDto("staff", "Staff", "Member", "Staff Member"))
        returning(Status.OK, userBody to sampleUser)
    } bindContract Method.PATCH to { id: String ->
        manageUser.then { request: Request ->
            val fields = userWrite(request)
            Response(Status.OK).with(
                userBody of
                    directory.updateUserProfile(id.userId(), fields.username, fields.firstName, fields.lastName, fields.displayName).dto(),
            )
        }
    }
    routes += "$basePath/users" / userPath / "status" meta {
        protected("SetUserStatus", "Activate or disable a user")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(statusBody to StatusDto("DISABLED"))
        returning(Status.OK, userBody to sampleUser)
    } bindContract Method.PUT to { id: String, _: String ->
        manageUser.then { request: Request ->
            Response(Status.OK).with(
                userBody of
                    (directory.setStatus(id.userId(), statusBody(request).status.status()) as io.github.castab.commerce.staff.User).dto(),
            )
        }
    }
    routes += "$basePath/users" / userPath / "roles" meta {
        protected("UserRoles", "List a user's assigned roles")
        returning(Status.OK, assignmentsBody to AssignmentsDto(listOf("commerce.manager")))
    } bindContract Method.GET to { id: String, _: String ->
        readUser.then { _: Request ->
            Response(Status.OK).with(assignmentsBody of AssignmentsDto(directory.assignedRoles(id.userId()).map { it.role.value }.sorted()))
        }
    }
    routes += "$basePath/users" / userPath / "roles" / rolePath meta {
        protected("AssignUserRole", "Assign an existing role to a user")
        returning(Status.NO_CONTENT)
    } bindContract Method.PUT to { id: String, _: String, key: String ->
        assignRole.then { _: Request ->
            directory.assignRole(id.userId(), key.roleKey())
            Response(Status.NO_CONTENT)
        }
    }
    routes += "$basePath/users" / userPath / "roles" / rolePath meta {
        protected("UnassignUserRole", "Remove a user's role")
        returning(Status.NO_CONTENT)
    } bindContract Method.DELETE to { id: String, _: String, key: String ->
        assignRole.then { _: Request ->
            directory.unassignRole(id.userId(), key.roleKey())
            Response(Status.NO_CONTENT)
        }
    }

    routes += "$basePath/services" meta {
        protected("ListServices", "List service identities")
        returning(Status.OK, servicesBody to ServicesDto(listOf(sampleService)))
    } bindContract Method.GET to
        readUser.then { _: Request ->
            Response(Status.OK).with(servicesBody of ServicesDto(directory.listServices().map { it.dto() }))
        }
    routes += "$basePath/services" meta {
        protected("CreateService", "Create a service identity without credentials")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(serviceWrite to ServiceWriteDto("worker"))
        returning(Status.CREATED, serviceBody to sampleService)
    } bindContract Method.POST to
        manageUser.then { request: Request ->
            Response(Status.CREATED).with(
                serviceBody of directory.createService(serviceWrite(request).service(ServiceId(UUID.randomUUID()))).dto(),
            )
        }
    routes += "$basePath/services" / servicePath meta {
        protected("GetService", "Get a service identity")
        returning(Status.OK, serviceBody to sampleService)
    } bindContract Method.GET to { id: String ->
        readUser.then { _: Request ->
            Response(Status.OK).with(
                serviceBody of (directory.getService(id.serviceId()) ?: throw CommerceFailure.NotFound("Service does not exist")).dto(),
            )
        }
    }
    routes += "$basePath/services" / servicePath meta {
        protected("RenameService", "Rename a service identity")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(serviceWrite to ServiceWriteDto("worker"))
        returning(Status.OK, serviceBody to sampleService)
    } bindContract Method.PATCH to { id: String ->
        manageUser.then { request: Request ->
            Response(Status.OK).with(serviceBody of directory.renameService(id.serviceId(), serviceWrite(request).name).dto())
        }
    }
    routes += "$basePath/services" / servicePath / "status" meta {
        protected("SetServiceStatus", "Activate or disable a service identity")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(statusBody to StatusDto("DISABLED"))
        returning(Status.OK, serviceBody to sampleService)
    } bindContract Method.PUT to { id: String, _: String ->
        manageUser.then { request: Request ->
            Response(Status.OK).with(
                serviceBody of
                    (
                        directory.setStatus(
                            id.serviceId(),
                            statusBody(request).status.status(),
                        ) as io.github.castab.commerce.staff.ServiceIdentity
                    ).dto(),
            )
        }
    }
    routes += "$basePath/services" / servicePath / "roles" meta {
        protected("ServiceRoles", "List a service identity's roles")
        returning(Status.OK, assignmentsBody to AssignmentsDto(listOf("commerce.manager")))
    } bindContract Method.GET to { id: String, _: String ->
        readUser.then { _: Request ->
            Response(Status.OK).with(
                assignmentsBody of AssignmentsDto(directory.assignedRoles(id.serviceId()).map { it.role.value }.sorted()),
            )
        }
    }
    routes += "$basePath/services" / servicePath / "roles" / rolePath meta {
        protected("AssignServiceRole", "Assign an existing role to a service identity")
        returning(Status.NO_CONTENT)
    } bindContract Method.PUT to { id: String, _: String, key: String ->
        assignRole.then { _: Request ->
            directory.assignRole(id.serviceId(), key.roleKey())
            Response(Status.NO_CONTENT)
        }
    }
    routes += "$basePath/services" / servicePath / "roles" / rolePath meta {
        protected("UnassignServiceRole", "Remove a service identity's role")
        returning(Status.NO_CONTENT)
    } bindContract Method.DELETE to { id: String, _: String, key: String ->
        assignRole.then { _: Request ->
            directory.unassignRole(id.serviceId(), key.roleKey())
            Response(Status.NO_CONTENT)
        }
    }

    routes += "$basePath/roles" meta {
        protected("ListRoles", "List role definitions")
        returning(Status.OK, rolesBody to RolesDto(listOf(sampleRole)))
    } bindContract Method.GET to
        readRole.then { _: Request ->
            Response(Status.OK).with(rolesBody of RolesDto(directory.listRoles().map { it.dto() }))
        }
    routes += "$basePath/roles" meta {
        protected("CreateRole", "Create a role definition")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(roleWrite to RoleWriteDto(sampleRole.key, sampleRole.displayName, sampleRole.description, sampleRole.permissions))
        returning(Status.CREATED, roleBody to sampleRole)
    } bindContract Method.POST to
        manageRole.then { request: Request ->
            Response(Status.CREATED).with(roleBody of directory.createRole(roleWrite(request).role()).dto())
        }
    routes += "$basePath/roles" / rolePath meta {
        protected("GetRole", "Get a role definition")
        returning(Status.OK, roleBody to sampleRole)
    } bindContract Method.GET to { key: String ->
        readRole.then { _: Request ->
            Response(Status.OK).with(
                roleBody of (directory.getRole(key.roleKey()) ?: throw CommerceFailure.NotFound("Role does not exist")).dto(),
            )
        }
    }
    routes += "$basePath/roles" / rolePath meta {
        protected("UpdateRole", "Update a role's display fields")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(roleProfile to RoleProfileDto("Manager", "Manages staff"))
        returning(Status.OK, roleBody to sampleRole)
    } bindContract Method.PATCH to { key: String ->
        manageRole.then { request: Request ->
            val fields = roleProfile(request)
            Response(Status.OK).with(roleBody of directory.updateRoleDetails(key.roleKey(), fields.displayName, fields.description).dto())
        }
    }
    routes += "$basePath/roles" / rolePath meta {
        protected("DeleteRole", "Delete an unassigned role")
        returning(Status.NO_CONTENT)
    } bindContract Method.DELETE to { key: String ->
        manageRole.then { _: Request ->
            directory.deleteRole(key.roleKey())
            Response(Status.NO_CONTENT)
        }
    }
    routes += "$basePath/roles" / rolePath / "permissions" meta {
        protected("ReplaceRolePermissions", "Replace a role's permission grants")
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(permissionKeys to PermissionKeysDto(sampleRole.permissions))
        returning(Status.OK, roleBody to sampleRole)
    } bindContract Method.PUT to { key: String, _: String ->
        manageRole.then { request: Request ->
            val keys = validating { permissionKeys(request).permissions.map { value -> PermissionKey(value) }.toSet() }
            Response(Status.OK).with(roleBody of directory.replaceRolePermissions(key.roleKey(), keys).dto())
        }
    }
    routes += "$basePath/permissions" meta {
        protected("ListPermissions", "List software-defined permissions")
        returning(Status.OK, permissionsBody to PermissionsDto(listOf(samplePermission)))
    } bindContract Method.GET to
        readRole.then { _: Request ->
            Response(Status.OK).with(permissionsBody of PermissionsDto(directory.permissionCatalog.definitions.map { it.dto() }))
        }

    return AuthorizationAdministrationHttpCapability(routes)
}
