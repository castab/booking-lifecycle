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

/** Extensible operation identifier. Business operations ordinarily check this, not a role. */
@JvmInline
value class PermissionKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Permission key must not be blank" }
    }
}

/** A named, application-supplied bundle of permissions. Permissions are copied on creation. */
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

/** Resolves the current commerce user after the application has authenticated a [UserId]. */
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
 * permissions. Resolvers are supplied by the consuming application.
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

    /** Create an offerings catalog or append categories and offerings to it. */
    val OfferingsManage: PermissionKey = PermissionKey("commerce.offerings.manage")

    /** Record a payment. */
    val PaymentRecord: PermissionKey = PermissionKey("commerce.payment.record")

    /** Record a refund. */
    val RefundRecord: PermissionKey = PermissionKey("commerce.refund.record")

    /** Read a staff user. */
    val UserRead: PermissionKey = PermissionKey("commerce.user.read")

    /** Manage staff users. */
    val UserManage: PermissionKey = PermissionKey("commerce.user.manage")

    /** Assign roles to staff users. */
    val RoleAssign: PermissionKey = PermissionKey("commerce.role.assign")
}
