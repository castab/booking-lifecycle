package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.Principal
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleAssignment
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.util.UUID

/** SQL stays internal; all methods join the caller's transaction. */
internal class AuthorizationRepository {
    fun users(tx: Transaction): List<User> =
        tx.handle
            .createQuery(
                "SELECT u.*, p.status FROM commerce.users u JOIN commerce.principals p USING (principal_kind, principal_id) ORDER BY u.normalized_username",
            ).map { row, _ -> user(tx, row) }
            .list()

    fun user(
        tx: Transaction,
        id: UserId,
    ): User? =
        tx.handle
            .createQuery(
                "SELECT u.*, p.status FROM commerce.users u JOIN commerce.principals p USING (principal_kind, principal_id) WHERE u.principal_id = :id",
            ).bind("id", id.value)
            .map { row, _ -> user(tx, row) }
            .findOne()
            .orElse(null)

    fun userByName(
        tx: Transaction,
        normalized: String,
    ): User? =
        tx.handle
            .createQuery(
                "SELECT u.*, p.status FROM commerce.users u JOIN commerce.principals p USING (principal_kind, principal_id) WHERE u.normalized_username = :name",
            ).bind("name", normalized)
            .map { row, _ -> user(tx, row) }
            .findOne()
            .orElse(null)

    fun services(tx: Transaction): List<ServiceIdentity> =
        tx.handle
            .createQuery(
                "SELECT s.*, p.status FROM commerce.service_identities s JOIN commerce.principals p USING (principal_kind, principal_id) ORDER BY s.name",
            ).map { row, _ -> service(tx, row) }
            .list()

    fun service(
        tx: Transaction,
        id: ServiceId,
    ): ServiceIdentity? =
        tx.handle
            .createQuery(
                "SELECT s.*, p.status FROM commerce.service_identities s JOIN commerce.principals p USING (principal_kind, principal_id) WHERE s.principal_id = :id",
            ).bind("id", id.value)
            .map { row, _ -> service(tx, row) }
            .findOne()
            .orElse(null)

    fun principal(
        tx: Transaction,
        id: PrincipalId,
    ): Principal? =
        when (id) {
            is UserId -> user(tx, id)
            is ServiceId -> service(tx, id)
        }

    /** Identity-only lookup shared by sessions; an orphan principal row is not an identity. */
    fun principalStatus(
        tx: Transaction,
        id: PrincipalId,
        lock: Boolean = false,
    ): PrincipalStatus? =
        tx.handle
            .createQuery(
                """SELECT p.status FROM commerce.principals p
               WHERE p.principal_kind = :kind AND p.principal_id = :id
                 AND ((p.principal_kind = 'USER' AND EXISTS
                     (SELECT 1 FROM commerce.users u WHERE u.principal_kind = p.principal_kind AND u.principal_id = p.principal_id))
                   OR (p.principal_kind = 'SERVICE' AND EXISTS
                     (SELECT 1 FROM commerce.service_identities s WHERE s.principal_kind = p.principal_kind AND s.principal_id = p.principal_id)))
               ${if (lock) "FOR SHARE OF p" else ""}""",
            ).bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
            ?.let(PrincipalStatus::valueOf)

    /** Raw stored keys, so a value that is no longer a well-formed [PermissionKey] can still be named. */
    fun storedPermissionKeys(tx: Transaction): Set<String> =
        tx.handle
            .createQuery("SELECT DISTINCT permission_key FROM commerce.role_permissions")
            .mapTo(String::class.java)
            .list()
            .toSet()

    /** Raw stored keys of one role, read before any is turned into a [PermissionKey]. */
    fun storedPermissionKeys(
        tx: Transaction,
        role: RoleKey,
    ): Set<String> =
        tx.handle
            .createQuery("SELECT permission_key FROM commerce.role_permissions WHERE role_key = :key")
            .bind("key", role.value)
            .mapTo(String::class.java)
            .list()
            .toSet()

    fun insertUser(
        tx: Transaction,
        user: User,
        normalized: String,
    ) {
        insertPrincipal(tx, user.id, user.status)
        tx.handle
            .createUpdate(
                "INSERT INTO commerce.users (principal_id, username, normalized_username, first_name, last_name, display_name) VALUES (:id, :username, :normalized, :first, :last, :display)",
            ).bind("id", user.id.value)
            .bind("username", user.username)
            .bind("normalized", normalized)
            .bind("first", user.firstName)
            .bind("last", user.lastName)
            .bind("display", user.displayName)
            .execute()
    }

    fun updateUser(
        tx: Transaction,
        user: User,
        normalized: String,
    ): Boolean =
        tx.handle
            .createUpdate(
                "UPDATE commerce.users SET username = :username, normalized_username = :normalized, first_name = :first, last_name = :last, display_name = :display WHERE principal_id = :id",
            ).bind("id", user.id.value)
            .bind("username", user.username)
            .bind("normalized", normalized)
            .bind("first", user.firstName)
            .bind("last", user.lastName)
            .bind("display", user.displayName)
            .execute() != 0

    fun insertService(
        tx: Transaction,
        service: ServiceIdentity,
    ) {
        insertPrincipal(tx, service.id, service.status)
        tx.handle
            .createUpdate("INSERT INTO commerce.service_identities (principal_id, name) VALUES (:id, :name)")
            .bind("id", service.id.value)
            .bind("name", service.name)
            .execute()
    }

    fun updateService(
        tx: Transaction,
        id: ServiceId,
        name: String,
    ): Boolean =
        tx.handle
            .createUpdate("UPDATE commerce.service_identities SET name = :name WHERE principal_id = :id")
            .bind("id", id.value)
            .bind("name", name)
            .execute() != 0

    fun updateStatus(
        tx: Transaction,
        id: PrincipalId,
        status: PrincipalStatus,
    ): Boolean =
        tx.handle
            .createUpdate("UPDATE commerce.principals SET status = :status WHERE principal_kind = :kind AND principal_id = :id")
            .bind("status", status.name)
            .bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .execute() != 0

    /** Whether [key] is defined, without reading its grants. */
    fun roleExists(
        tx: Transaction,
        key: RoleKey,
    ): Boolean =
        tx.handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM commerce.roles WHERE role_key = :key)")
            .bind("key", key.value)
            .mapTo(Boolean::class.java)
            .one()

    fun roles(tx: Transaction): List<RoleDefinition> =
        tx.handle
            .createQuery("SELECT * FROM commerce.roles ORDER BY role_key")
            .map { row, _ -> role(tx, row) }
            .list()

    fun role(
        tx: Transaction,
        key: RoleKey,
    ): RoleDefinition? =
        tx.handle
            .createQuery("SELECT * FROM commerce.roles WHERE role_key = :key")
            .bind("key", key.value)
            .map { row, _ -> role(tx, row) }
            .findOne()
            .orElse(null)

    fun insertRole(
        tx: Transaction,
        role: RoleDefinition,
    ) {
        tx.handle
            .createUpdate("INSERT INTO commerce.roles (role_key, display_name, description) VALUES (:key, :name, :description)")
            .bind("key", role.key.value)
            .bind("name", role.displayName)
            .bind("description", role.description)
            .execute()
        replacePermissions(tx, role.key, role.permissions)
    }

    fun updateRole(
        tx: Transaction,
        role: RoleDefinition,
    ): Boolean =
        tx.handle
            .createUpdate("UPDATE commerce.roles SET display_name = :name, description = :description WHERE role_key = :key")
            .bind("key", role.key.value)
            .bind("name", role.displayName)
            .bind("description", role.description)
            .execute() != 0

    fun replacePermissions(
        tx: Transaction,
        key: RoleKey,
        permissions: Set<PermissionKey>,
    ) {
        tx.handle
            .createUpdate("DELETE FROM commerce.role_permissions WHERE role_key = :key")
            .bind("key", key.value)
            .execute()
        permissions.forEach { permission ->
            tx.handle
                .createUpdate("INSERT INTO commerce.role_permissions (role_key, permission_key) VALUES (:key, :permission)")
                .bind("key", key.value)
                .bind("permission", permission.value)
                .execute()
        }
    }

    fun assignedCount(
        tx: Transaction,
        key: RoleKey,
    ): Long =
        tx.handle
            .createQuery("SELECT count(*) FROM commerce.principal_roles WHERE role_key = :key")
            .bind("key", key.value)
            .mapTo(Long::class.java)
            .one()

    fun deleteRole(
        tx: Transaction,
        key: RoleKey,
    ): Boolean {
        tx.handle
            .createUpdate("DELETE FROM commerce.role_permissions WHERE role_key = :key")
            .bind("key", key.value)
            .execute()
        return tx.handle
            .createUpdate("DELETE FROM commerce.roles WHERE role_key = :key")
            .bind("key", key.value)
            .execute() != 0
    }

    fun assignments(
        tx: Transaction,
        id: PrincipalId,
    ): Set<RoleAssignment> =
        tx.handle
            .createQuery(
                "SELECT role_key FROM commerce.principal_roles WHERE principal_kind = :kind AND principal_id = :id ORDER BY role_key",
            ).bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .map { row, _ -> RoleAssignment(RoleKey(row.getString("role_key"))) }
            .list()
            .toSet()

    fun assign(
        tx: Transaction,
        id: PrincipalId,
        key: RoleKey,
    ) {
        tx.handle
            .createUpdate(
                "INSERT INTO commerce.principal_roles (principal_kind, principal_id, role_key) VALUES (:kind, :id, :key) ON CONFLICT DO NOTHING",
            ).bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .bind("key", key.value)
            .execute()
    }

    fun unassign(
        tx: Transaction,
        id: PrincipalId,
        key: RoleKey,
    ) {
        tx.handle
            .createUpdate(
                "DELETE FROM commerce.principal_roles WHERE principal_kind = :kind AND principal_id = :id AND role_key = :key",
            ).bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .bind("key", key.value)
            .execute()
    }

    private fun insertPrincipal(
        tx: Transaction,
        id: PrincipalId,
        status: PrincipalStatus,
    ) {
        tx.handle
            .createUpdate("INSERT INTO commerce.principals (principal_kind, principal_id, status) VALUES (:kind, :id, :status)")
            .bind("kind", PrincipalIdColumns.kind(id))
            .bind("id", PrincipalIdColumns.value(id))
            .bind("status", status.name)
            .execute()
    }

    private fun user(
        tx: Transaction,
        row: ResultSet,
    ) = User(
        UserId(row.getObject("principal_id", UUID::class.java)),
        row.getString("username"),
        row.getString("first_name"),
        row.getString("last_name"),
        row.getString("display_name"),
        PrincipalStatus.valueOf(row.getString("status")),
        assignments(tx, UserId(row.getObject("principal_id", UUID::class.java))),
    )

    private fun service(
        tx: Transaction,
        row: ResultSet,
    ) = ServiceIdentity(
        ServiceId(row.getObject("principal_id", UUID::class.java)),
        row.getString("name"),
        PrincipalStatus.valueOf(row.getString("status")),
        assignments(tx, ServiceId(row.getObject("principal_id", UUID::class.java))),
    )

    private fun role(
        tx: Transaction,
        row: ResultSet,
    ): RoleDefinition {
        val key = RoleKey(row.getString("role_key"))
        val permissions =
            tx.handle
                .createQuery("SELECT permission_key FROM commerce.role_permissions WHERE role_key = :key ORDER BY permission_key")
                .bind("key", key.value)
                .map { result, _ -> PermissionKey(result.getString("permission_key")) }
                .list()
                .toSet()
        return RoleDefinition(key, row.getString("display_name"), row.getString("description"), permissions)
    }
}
