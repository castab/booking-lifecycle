package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.Principal
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalResolver
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleAssignment
import io.github.castab.commerce.staff.RoleBasedPermissionResolver
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.RoleResolver
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.util.Locale

/**
 * Live principal and RBAC administration. Mutations with [Transaction] join application
 * credential or profile writes. Disabling either principal kind revokes its sessions in
 * the same transaction. Roles and permissions are never cached in sessions.
 */
class AuthorizationDirectory internal constructor(
    private val transactor: Transactor,
    private val repository: AuthorizationRepository,
    private val sessions: SessionManager,
    val permissionCatalog: PermissionCatalog,
) {
    val principalResolver = PrincipalResolver { id -> transactor.inTransaction { repository.principal(it, id) } }

    /** Live role definitions, through the same catalog-validating read as [getRole]. */
    val roleResolver = RoleResolver { key -> transactor.inTransaction { validatedRole(it, key) } }
    private val roleBasedPermissionResolver = RoleBasedPermissionResolver(principalResolver, roleResolver)

    /**
     * Live effective permissions, resolved on every call. The result is always a subset of
     * [permissionCatalog]; anything else fails closed with [IllegalStateException].
     */
    val permissionResolver: PermissionResolver =
        PermissionResolver { id -> roleBasedPermissionResolver.permissionsFor(id).also(permissionCatalog::requireEffective) }

    fun listUsers(): List<User> = transactor.inTransaction { repository.users(it) }

    fun getUser(id: UserId): User? = transactor.inTransaction { repository.user(it, id) }

    fun findUserByUsername(username: String): User? = transactor.inTransaction { repository.userByName(it, normalizeUsername(username)) }

    fun createUser(user: User): User = transactor.inTransaction { createUser(it, user) }

    fun createUser(
        transaction: Transaction,
        user: User,
    ): User {
        if (user.roles.isNotEmpty()) throw CommerceFailure.ValidationFailed("Assign roles separately from creating a user")
        val normalized = normalizeUsername(user.username)
        val canonical = User(user.id, user.username.trim(), user.firstName, user.lastName, user.displayName, user.status, emptySet())
        try {
            repository.insertUser(transaction, canonical, normalized)
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("User identifier or username already exists", e)
            throw e
        }
        return canonical
    }

    fun updateUserProfile(
        id: UserId,
        username: String,
        firstName: String?,
        lastName: String?,
        displayName: String,
    ): User = transactor.inTransaction { updateUserProfile(it, id, username, firstName, lastName, displayName) }

    /** Updates profile fields only. Status and assignments have dedicated operations. */
    fun updateUserProfile(
        transaction: Transaction,
        id: UserId,
        username: String,
        firstName: String?,
        lastName: String?,
        displayName: String,
    ): User {
        val previous = repository.user(transaction, id) ?: throw CommerceFailure.NotFound("User does not exist")
        val normalized = normalizeUsername(username)
        val canonical = validating { User(id, username.trim(), firstName, lastName, displayName, previous.status, previous.roles) }
        try {
            if (!repository.updateUser(transaction, canonical, normalized)) throw CommerceFailure.NotFound("User does not exist")
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Username already exists", e)
            throw e
        }
        return repository.user(transaction, id)!!
    }

    fun listServices(): List<ServiceIdentity> = transactor.inTransaction { repository.services(it) }

    fun getService(id: ServiceId): ServiceIdentity? = transactor.inTransaction { repository.service(it, id) }

    fun createService(service: ServiceIdentity): ServiceIdentity = transactor.inTransaction { createService(it, service) }

    fun createService(
        transaction: Transaction,
        service: ServiceIdentity,
    ): ServiceIdentity {
        if (service.roles.isNotEmpty()) throw CommerceFailure.ValidationFailed("Assign roles separately from creating a service")
        try {
            repository.insertService(transaction, service)
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Service identifier already exists", e)
            throw e
        }
        return service
    }

    fun renameService(
        id: ServiceId,
        name: String,
    ): ServiceIdentity = transactor.inTransaction { renameService(it, id, name) }

    fun renameService(
        transaction: Transaction,
        id: ServiceId,
        name: String,
    ): ServiceIdentity {
        if (name.isBlank()) throw CommerceFailure.ValidationFailed("Service name must not be blank")
        if (!repository.updateService(transaction, id, name)) throw CommerceFailure.NotFound("Service does not exist")
        return repository.service(transaction, id)!!
    }

    fun setStatus(
        id: PrincipalId,
        status: PrincipalStatus,
    ): Principal = transactor.inTransaction { setStatus(it, id, status) }

    fun setStatus(
        transaction: Transaction,
        id: PrincipalId,
        status: PrincipalStatus,
    ): Principal {
        if (!repository.updateStatus(transaction, id, status)) throw CommerceFailure.NotFound("Principal does not exist")
        if (status == PrincipalStatus.DISABLED) sessions.revokeAll(transaction, id)
        return repository.principal(transaction, id)!!
    }

    /**
     * Every role definition. One stored grant outside [permissionCatalog] fails the whole read
     * closed with [IllegalStateException]; no role or grant is silently left out.
     */
    fun listRoles(): List<RoleDefinition> = transactor.inTransaction { validatedRoles(it) }

    /** The role definition, or `null`; a stored grant outside [permissionCatalog] fails closed. */
    fun getRole(key: RoleKey): RoleDefinition? = transactor.inTransaction { validatedRole(it, key) }

    /**
     * The one boundary through which a persisted [RoleDefinition] leaves the directory, so
     * every role it describes grants only known permissions. Raw stored values are checked
     * first, so a malformed key (`Legacy.Key`) fails with the stored-grant diagnostic naming
     * it rather than as a [PermissionKey] it could never become. The hydrated grants are
     * checked again, so a grant written between the two reads cannot slip through. Unknown
     * grants are never repaired or filtered.
     */
    private fun validatedRole(
        transaction: Transaction,
        key: RoleKey,
    ): RoleDefinition? {
        permissionCatalog.validatePersisted(repository.storedPermissionKeys(transaction, key))
        return repository.role(transaction, key)?.also(::requireKnownGrants)
    }

    /** [validatedRole] for every role: all stored grants are checked before any role is built. */
    private fun validatedRoles(transaction: Transaction): List<RoleDefinition> {
        permissionCatalog.validatePersisted(repository.storedPermissionKeys(transaction))
        return repository.roles(transaction).onEach(::requireKnownGrants)
    }

    private fun requireKnownGrants(role: RoleDefinition) = permissionCatalog.validatePersisted(role.permissions.map { it.value })

    fun createRole(role: RoleDefinition): RoleDefinition = transactor.inTransaction { createRole(it, role) }

    fun createRole(
        transaction: Transaction,
        role: RoleDefinition,
    ): RoleDefinition {
        permissionCatalog.validate(role.permissions)
        try {
            repository.insertRole(transaction, role)
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Role already exists", e)
            throw e
        }
        return role
    }

    fun updateRoleDetails(
        key: RoleKey,
        displayName: String,
        description: String?,
    ): RoleDefinition = transactor.inTransaction { updateRoleDetails(it, key, displayName, description) }

    /**
     * Changes name and description; use [replaceRolePermissions] for grants. A role holding a
     * grant outside [permissionCatalog] fails closed rather than being rewritten with it.
     */
    fun updateRoleDetails(
        transaction: Transaction,
        key: RoleKey,
        displayName: String,
        description: String?,
    ): RoleDefinition {
        val previous = validatedRole(transaction, key) ?: throw CommerceFailure.NotFound("Role does not exist")
        val role = validating { RoleDefinition(key, displayName, description, previous.permissions) }
        if (!repository.updateRole(transaction, role)) throw CommerceFailure.NotFound("Role does not exist")
        return validatedRole(transaction, role.key)!!
    }

    fun replaceRolePermissions(
        key: RoleKey,
        permissions: Set<PermissionKey>,
    ): RoleDefinition = transactor.inTransaction { replaceRolePermissions(it, key, permissions) }

    /**
     * Replaces every grant with known [permissions]. The previous grants are not read, so this
     * is how an administrator replaces a role's stale or unknown stored grants; the returned
     * role is validated like any other read.
     */
    fun replaceRolePermissions(
        transaction: Transaction,
        key: RoleKey,
        permissions: Set<PermissionKey>,
    ): RoleDefinition {
        permissionCatalog.validate(permissions)
        if (!repository.roleExists(transaction, key)) throw CommerceFailure.NotFound("Role does not exist")
        repository.replacePermissions(transaction, key, permissions)
        return validatedRole(transaction, key)!!
    }

    fun deleteRole(key: RoleKey) = transactor.inTransaction { deleteRole(it, key) }

    /** Rejects assigned roles with conflict; never cascades authorization changes. */
    fun deleteRole(
        transaction: Transaction,
        key: RoleKey,
    ) {
        if (!repository.roleExists(transaction, key)) throw CommerceFailure.NotFound("Role does not exist")
        if (repository.assignedCount(transaction, key) != 0L) throw CommerceFailure.Conflict("Role is assigned to principals")
        repository.deleteRole(transaction, key)
    }

    fun assignedRoles(id: PrincipalId): Set<RoleAssignment> = transactor.inTransaction { assignedRoles(it, id) }

    fun assignedRoles(
        transaction: Transaction,
        id: PrincipalId,
    ): Set<RoleAssignment> {
        if (repository.principal(transaction, id) == null) throw CommerceFailure.NotFound("Principal does not exist")
        return repository.assignments(transaction, id)
    }

    fun assignRole(
        id: PrincipalId,
        key: RoleKey,
    ) = transactor.inTransaction { assignRole(it, id, key) }

    /**
     * Idempotent when already assigned. A role holding a grant outside [permissionCatalog]
     * fails closed and is not assigned.
     */
    fun assignRole(
        transaction: Transaction,
        id: PrincipalId,
        key: RoleKey,
    ) {
        if (repository.principal(transaction, id) == null) throw CommerceFailure.NotFound("Principal does not exist")
        if (validatedRole(transaction, key) == null) throw CommerceFailure.NotFound("Role does not exist")
        repository.assign(transaction, id, key)
    }

    fun unassignRole(
        id: PrincipalId,
        key: RoleKey,
    ) = transactor.inTransaction { unassignRole(it, id, key) }

    /** Idempotent when absent; the principal must exist. */
    fun unassignRole(
        transaction: Transaction,
        id: PrincipalId,
        key: RoleKey,
    ) {
        if (repository.principal(transaction, id) == null) throw CommerceFailure.NotFound("Principal does not exist")
        repository.unassign(transaction, id, key)
    }
}

/** ASCII login name: trim surrounding whitespace, then lowercase with Locale.ROOT. */
fun normalizeUsername(username: String): String {
    val trimmed = username.trim()
    if (!trimmed.matches(Regex("[A-Za-z0-9._-]+"))) {
        throw CommerceFailure.ValidationFailed("Username must use ASCII letters, digits, period, underscore, or hyphen")
    }
    return trimmed.lowercase(Locale.ROOT)
}
