package io.github.castab.commerce.staff

import java.util.Collections

/** Extensible role identifier. Applications may define keys outside the commerce namespace. */
@JvmInline
value class RoleKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Role key must not be blank" }
    }
}

/**
 * The one canonical identity of an operation permission, used by permission definitions,
 * role grants, resolvers, and enforcement alike. Business operations ordinarily check this,
 * not a role.
 *
 * A key is one or more dot-separated segments; each segment is lowercase ASCII letters and
 * digits, optionally joined by single hyphens or underscores, for example `users.read` or
 * `catering.inquiries.assign`. Values are taken exactly as given and never normalized:
 * `Users.Create` is rejected, not lowercased.
 */
@JvmInline
value class PermissionKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Permission key must not be blank" }
        require(value.matches(dottedKey)) {
            "Permission key '$value' must be lowercase dot-separated segments of letters and digits, " +
                "optionally joined by '-' or '_'"
        }
    }
}

/**
 * A stable, machine-readable grouping of related permissions, such as `commerce.roles`,
 * for presentation in administrative interfaces. It uses the same syntax as [PermissionKey]
 * and confers no authority.
 */
@JvmInline
value class PermissionGroup(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Permission group must not be blank" }
        require(value.matches(dottedKey)) {
            "Permission group '$value' must be lowercase dot-separated segments of letters and digits, " +
                "optionally joined by '-' or '_'"
        }
    }
}

private val dottedKey = Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*(?:\\.[a-z0-9]+(?:[-_][a-z0-9]+)*)*")

/** A named bundle of permissions, supplied or persisted by the application or runtime. Permissions are copied on creation. */
class RoleDefinition(
    val key: RoleKey,
    val displayName: String,
    val description: String?,
    permissions: Set<PermissionKey>,
) {
    val permissions: Set<PermissionKey> = Collections.unmodifiableSet(LinkedHashSet(permissions))

    init {
        require(displayName.isNotBlank()) { "Role display name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RoleDefinition &&
            key == other.key &&
            displayName == other.displayName &&
            description == other.description &&
            permissions == other.permissions

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + permissions.hashCode()
        return result
    }

    override fun toString(): String =
        "RoleDefinition(key=$key, displayName=$displayName, description=$description, permissions=$permissions)"
}

/**
 * Human-readable metadata for a permission implemented by runtime or application code.
 * Describing a permission confers no authority; it names the vocabulary that role grants
 * and enforcement use through [key].
 */
data class PermissionDefinition(
    val key: PermissionKey,
    val displayName: String,
    val description: String,
    val group: PermissionGroup,
) {
    init {
        require(displayName.isNotBlank()) { "Permission display name must not be blank" }
        require(description.isNotBlank()) { "Permission description must not be blank" }
    }
}

/** Resolves the current commerce user after authentication established a [UserId]. */
fun interface UserResolver {
    fun resolve(userId: UserId): User?
}

/** Resolves the current human or service principal after authentication. */
fun interface PrincipalResolver {
    fun resolve(principalId: PrincipalId): Principal?
}

/** Resolves the current definition of an assigned role. */
fun interface RoleResolver {
    fun resolve(role: RoleKey): RoleDefinition?
}

/** Supplies the permissions currently available to an authenticated principal. */
fun interface PermissionResolver {
    fun permissionsFor(principalId: PrincipalId): Set<PermissionKey>
}

/**
 * Additive, default-deny role resolution for humans and services. Missing or disabled
 * principals, mismatched identities, and missing or mismatched role definitions grant
 * nothing. An unresolved role is skipped; other resolved roles may still grant
 * permissions. Resolvers may be supplied by a consuming application or its runtime.
 */
class RoleBasedPermissionResolver(
    private val principalResolver: PrincipalResolver,
    private val roleResolver: RoleResolver,
) : PermissionResolver {
    override fun permissionsFor(principalId: PrincipalId): Set<PermissionKey> {
        val principal = principalResolver.resolve(principalId) ?: return emptySet()
        if (principal.id != principalId) return emptySet()
        if (principal.status != PrincipalStatus.ACTIVE) return emptySet()

        val permissions =
            principal.roles
                .mapNotNull { assignment ->
                    roleResolver.resolve(assignment.role)?.takeIf { it.key == assignment.role }
                }.flatMapTo(LinkedHashSet()) { it.permissions }
        return Collections.unmodifiableSet(permissions)
    }
}

/** Whether this authenticated principal currently has [permission]. */
fun PrincipalId.can(
    permission: PermissionKey,
    permissionResolver: PermissionResolver,
): Boolean = permission in permissionResolver.permissionsFor(this)

/** Conventional staff role keys; applications supply their actual definitions and grants. */
object CommerceRoles {
    /** Conventional administrator role key, without implied permissions. */
    val Administrator: RoleKey = RoleKey("commerce.administrator")

    /** Conventional manager role key, without implied permissions. */
    val Manager: RoleKey = RoleKey("commerce.manager")

    /** Conventional supervisor role key, without implied permissions. */
    val Supervisor: RoleKey = RoleKey("commerce.supervisor")

    /** Conventional employee role key, without implied permissions. */
    val Employee: RoleKey = RoleKey("commerce.employee")
}

/** Conventional keys for operations represented by the commerce domains. */
object CommercePermissions {
    /** Read a booking. */
    val BookingRead: PermissionKey = PermissionKey("commerce.booking.read")

    /** Modify a booking. */
    val BookingModify: PermissionKey = PermissionKey("commerce.booking.modify")

    /** Read a financial document. */
    val FinancialDocumentRead: PermissionKey = PermissionKey("commerce.financial-document.read")

    /** Create a financial document. */
    val FinancialDocumentCreate: PermissionKey = PermissionKey("commerce.financial-document.create")

    /** Approve, replace, reactivate, or withdraw a financial lineage's deposit requirement. */
    val DepositRequirementManage: PermissionKey = PermissionKey("commerce.deposit-requirement.manage")

    /** Record a payment. */
    val PaymentRecord: PermissionKey = PermissionKey("commerce.payment.record")

    /** Record a refund. */
    val RefundRecord: PermissionKey = PermissionKey("commerce.refund.record")

    /** Read a human or service principal. */
    val PrincipalRead: PermissionKey = PermissionKey("commerce.principal.read")

    /** Manage human or service principal identity and status. */
    val PrincipalManage: PermissionKey = PermissionKey("commerce.principal.manage")

    /** Inspect role definitions and the software-defined permission catalog. */
    val RoleRead: PermissionKey = PermissionKey("commerce.role.read")

    /** Create, change, or remove role definitions and their permission sets. */
    val RoleManage: PermissionKey = PermissionKey("commerce.role.manage")

    /** Assign existing roles to human or service principals. */
    val RoleAssign: PermissionKey = PermissionKey("commerce.role.assign")
}
