package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionGroup
import io.github.castab.commerce.staff.PermissionKey
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * The complete authorization vocabulary of one running application: every software-defined
 * permission it knows, contributed by the runtime and the application.
 *
 * The catalog answers "which permission keys exist?" and never "does a principal hold one?";
 * that is the `PermissionResolver`'s question. Knowing a key confers no authority.
 *
 * A catalog is immutable once built and safe for concurrent reads. [definitions] are ordered
 * by key, independent of registration order. Duplicate keys are rejected and named, so two
 * contributors can never give the same key different meanings. Nothing is persisted.
 */
class PermissionCatalog(
    definitions: Collection<PermissionDefinition>,
) {
    /** Every known definition, ordered by key. */
    val definitions: List<PermissionDefinition>
    private val byKey: Map<PermissionKey, PermissionDefinition>

    /**
     * `sha256:` followed by the lowercase hex digest of a canonical encoding of [definitions]
     * (each key, group, display name, and description, in key order). Identical contents
     * always share a revision, whatever their registration order; any added, removed, or
     * changed definition changes it. For diagnostics and compatibility checks, never
     * authorization.
     */
    val revision: String

    init {
        val duplicates =
            definitions
                .groupingBy { it.key }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .map { it.value }
                .sorted()
        require(duplicates.isEmpty()) { "Duplicate permission keys: ${duplicates.joinToString()}" }
        this.definitions = Collections.unmodifiableList(definitions.sortedBy { it.key.value })
        byKey = this.definitions.associateBy { it.key }
        revision = revisionOf(this.definitions)
    }

    /** The definition registered for [key], or `null` when this application does not know it. */
    fun find(key: PermissionKey): PermissionDefinition? = byKey[key]

    /** Whether [key] is registered in this application's vocabulary. */
    operator fun contains(key: PermissionKey): Boolean = key in byKey

    internal fun validate(keys: Set<PermissionKey>) {
        val unknown = keys.filterNot { it in byKey }.map { it.value }.sorted()
        if (unknown.isNotEmpty()) throw CommerceFailure.ValidationFailed("Unknown permissions: ${unknown.joinToString()}")
    }

    /**
     * Fails closed when effective permissions resolved for enforcement or reporting include a
     * key this catalog does not define. Unknown keys are never filtered out: a resolver that
     * disagrees with the catalog is a broken configuration, not a principal without a grant.
     */
    internal fun requireEffective(keys: Set<PermissionKey>) {
        val unknown = keys.filterNot { it in byKey }.map { it.value }.sorted()
        check(unknown.isEmpty()) { "Effective permissions include keys missing from PermissionCatalog: ${unknown.joinToString()}" }
    }

    /** Fails closed when stored grants name a key, well-formed or not, that this catalog does not define. */
    internal fun validatePersisted(storedKeys: Collection<String>) {
        val known = byKey.keys.mapTo(HashSet()) { it.value }
        val unknown = storedKeys.filterNot { it in known }.distinct().sorted()
        if (unknown.isNotEmpty()) error("Stored role permissions missing from PermissionCatalog: ${unknown.joinToString()}")
    }

    companion object {
        /**
         * Composes one catalog from several contributions, for example
         * `PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)`.
         * A key contributed twice, even with identical metadata, is rejected.
         */
        @JvmStatic
        fun of(vararg contributions: Collection<PermissionDefinition>): PermissionCatalog = PermissionCatalog(contributions.flatMap { it })

        private fun revisionOf(definitions: List<PermissionDefinition>): String {
            val canonical = ByteArrayOutputStream()

            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                canonical.write("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                canonical.write(bytes)
            }
            field("commerce.permission-catalog.v1")
            field(definitions.size.toString())
            definitions.forEach { definition ->
                field(definition.key.value)
                field(definition.group.value)
                field(definition.displayName)
                field(definition.description)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return "sha256:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Permissions of runtime capabilities that are not commerce-domain operations, and so are
 * not part of the domain's `CommercePermissions`. Each is defined in
 * [commercePermissionDefinitions] like every other runtime permission.
 */
object RuntimePermissions {
    /**
     * Create and revoke service credentials. A credential authenticates as its service, with
     * all of that service's current roles, so this is as sensitive as `RoleAssign`: grant it
     * only to principals trusted to act as any service. Reading credential metadata needs
     * `PrincipalRead`.
     */
    val ServiceCredentialManage: PermissionKey = PermissionKey("commerce.service-credential.manage")
}

private val bookings = PermissionGroup("commerce.bookings")
private val financialDocuments = PermissionGroup("commerce.financial-documents")
private val offerings = PermissionGroup("commerce.offerings")
private val payments = PermissionGroup("commerce.payments")
private val principals = PermissionGroup("commerce.principals")
private val roles = PermissionGroup("commerce.roles")

/**
 * The runtime's own permission vocabulary: every [CommercePermissions] and
 * [RuntimePermissions] key, described.
 * These keys denote code capabilities, never database-created permissions. Runtime routes
 * enforce the offerings, principal, role, and service-credential keys; the booking, financial-document, payment,
 * and refund keys are conventional names for operations that applications enforce.
 *
 * Every permission a runtime capability enforces must be defined here, including runtime
 * infrastructure permissions that are not [CommercePermissions] (for example the
 * administration of service credentials), with a group and a description. Omitting one is
 * not silent: `AccessControl.requirePermission` rejects an unregistered key when the
 * capability is composed.
 */
val commercePermissionDefinitions: List<PermissionDefinition> =
    listOf(
        PermissionDefinition(CommercePermissions.BookingRead, "Read bookings", "View bookings managed by the application.", bookings),
        PermissionDefinition(CommercePermissions.BookingModify, "Modify bookings", "Change bookings managed by the application.", bookings),
        PermissionDefinition(
            CommercePermissions.FinancialDocumentRead,
            "Read financial documents",
            "View estimates, quotes, and invoices.",
            financialDocuments,
        ),
        PermissionDefinition(
            CommercePermissions.FinancialDocumentCreate,
            "Create financial documents",
            "Create estimates, quotes, and invoices.",
            financialDocuments,
        ),
        PermissionDefinition(
            CommercePermissions.OfferingsManage,
            "Manage offerings",
            "Create offerings catalogs; add, update, retire, and restore their categories and offerings; and view retired entries.",
            offerings,
        ),
        PermissionDefinition(CommercePermissions.PaymentRecord, "Record payments", "Record money received from payers.", payments),
        PermissionDefinition(CommercePermissions.RefundRecord, "Record refunds", "Record money returned to payers.", payments),
        PermissionDefinition(
            CommercePermissions.PrincipalRead,
            "Read principals",
            "View staff users, service identities, and their role assignments.",
            principals,
        ),
        PermissionDefinition(
            CommercePermissions.PrincipalManage,
            "Manage principals",
            "Create staff users and service identities, change their profiles, and enable or disable them.",
            principals,
        ),
        PermissionDefinition(
            CommercePermissions.RoleRead,
            "Read roles and permissions",
            "View role definitions and the permission catalog.",
            roles,
        ),
        PermissionDefinition(
            CommercePermissions.RoleManage,
            "Manage role definitions",
            "Create, change, and delete role definitions and their permission grants.",
            roles,
        ),
        PermissionDefinition(
            CommercePermissions.RoleAssign,
            "Assign roles",
            "Assign roles to, and remove roles from, staff users and service identities.",
            roles,
        ),
        PermissionDefinition(
            RuntimePermissions.ServiceCredentialManage,
            "Manage service credentials",
            "Create and revoke the credentials with which service identities authenticate.",
            principals,
        ),
    )
