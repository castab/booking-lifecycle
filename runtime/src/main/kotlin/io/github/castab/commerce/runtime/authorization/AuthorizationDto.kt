package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentialId
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val displayName: String,
    val status: String,
    val roles: List<String>,
)

@Serializable
data class UsersDto(
    val users: List<UserDto>,
)

@Serializable
data class UserWriteDto(
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val displayName: String,
)

@Serializable
data class StatusDto(
    val status: String,
)

@Serializable
data class ServiceDto(
    val id: String,
    val name: String,
    val status: String,
    val roles: List<String>,
)

@Serializable
data class ServicesDto(
    val services: List<ServiceDto>,
)

@Serializable
data class ServiceWriteDto(
    val name: String,
)

@Serializable
data class RoleDto(
    val key: String,
    val displayName: String,
    val description: String?,
    val permissions: List<String>,
)

@Serializable
data class RolesDto(
    val roles: List<RoleDto>,
)

@Serializable
data class RoleWriteDto(
    val key: String,
    val displayName: String,
    val description: String?,
    val permissions: List<String>,
)

@Serializable
data class RoleProfileDto(
    val displayName: String,
    val description: String?,
)

@Serializable
data class PermissionKeysDto(
    val permissions: List<String>,
)

@Serializable
data class PermissionDto(
    val key: String,
    val displayName: String,
    val description: String?,
)

@Serializable
data class PermissionsDto(
    val permissions: List<PermissionDto>,
)

@Serializable
data class AssignmentsDto(
    val roles: List<String>,
)

internal fun User.dto() =
    UserDto(
        id.value.toString(),
        username,
        firstName,
        lastName,
        displayName,
        status.name,
        roles
            .map {
                it.role.value
            }.sorted(),
    )

internal fun ServiceIdentity.dto() = ServiceDto(id.value.toString(), name, status.name, roles.map { it.role.value }.sorted())

internal fun RoleDefinition.dto() = RoleDto(key.value, displayName, description, permissions.map { it.value }.sorted())

internal fun PermissionDefinition.dto() = PermissionDto(key.value, displayName, description)

internal fun UserWriteDto.user(id: UserId) =
    validating {
        User(id, username, firstName, lastName, displayName, PrincipalStatus.ACTIVE, emptySet())
    }

internal fun ServiceWriteDto.service(id: ServiceId) = validating { ServiceIdentity(id, name, PrincipalStatus.ACTIVE, emptySet()) }

internal fun RoleWriteDto.role() =
    validating {
        RoleDefinition(RoleKey(key), displayName, description, permissions.map(::PermissionKey).toSet())
    }

internal fun String.userId() = validating { UserId(UUID.fromString(this)) }

internal fun String.serviceId() = validating { ServiceId(UUID.fromString(this)) }

internal fun String.serviceCredentialId() = validating { ServiceCredentialId(UUID.fromString(this)) }

internal fun String.roleKey() = validating { RoleKey(this) }

internal fun String.status() = validating { PrincipalStatus.valueOf(this) }
