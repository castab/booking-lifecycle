package io.github.castab.commerce.runtime.serviceauth

import io.github.castab.commerce.staff.ServiceId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Stable, non-secret identity of one [ServiceCredential]. Administrators list and revoke
 * credentials by it; it never authenticates anything by itself.
 */
@JvmInline
value class ServiceCredentialId(
    val value: UUID,
)

/**
 * One long-lived credential of a SERVICE principal, as administrators see it.
 *
 * A credential proves a service's identity when it obtains a service access token. It
 * grants nothing by itself: the service's current roles decide what it may do. A service may
 * hold several credentials at once, which is how a consumer rotates to a new credential
 * before the old one is revoked.
 *
 * It carries no secret material. The secret exists only in the [IssuedServiceCredential]
 * that created it, and only an Argon2id hash of it is stored.
 *
 * A credential is active until it is revoked ([revokedAt]); revocation is permanent.
 */
data class ServiceCredential(
    val id: ServiceCredentialId,
    val serviceId: ServiceId,
    val label: String,
    val createdAt: Instant,
    val revokedAt: Instant?,
) {
    init {
        require(label.isNotBlank() && label.length <= MAXIMUM_LABEL_LENGTH) {
            "Service credential label must be between 1 and $MAXIMUM_LABEL_LENGTH characters"
        }
        require(revokedAt == null || !revokedAt.isBefore(createdAt)) {
            "Service credential ${id.value} cannot be revoked before it was created"
        }
    }

    /** Whether the credential can still authenticate its service. */
    val active: Boolean get() = revokedAt == null

    companion object {
        /** The longest accepted label. */
        const val MAXIMUM_LABEL_LENGTH: Int = 200
    }
}

/**
 * The secret of one [ServiceCredential], presented by the service when it requests an access
 * token.
 *
 * Its text is `<credential id>.<verifier>`: the credential's public identifier, which
 * selects the one stored hash to check, followed by [VERIFIER_BYTES] bytes from
 * [SecureRandom] as unpadded base64url. Only the verifier is secret, and only an Argon2id
 * hash of it is stored, so the secret cannot be recovered from the database.
 *
 * Instances exist only for well-formed values: a freshly created secret, or [parse] of text a
 * client presented. [value] is the secret itself; deliver it to the service's operator once
 * and never persist or log it. [toString] never reveals it, and equality compares in
 * constant time.
 */
class ServiceCredentialSecret private constructor(
    /** The credential this secret claims to belong to. Not secret. */
    val credentialId: ServiceCredentialId,
    private val verifier: String,
) {
    /** The raw secret. Deliver it to the service's operator; never persist or log it. */
    val value: String get() = "${credentialId.value}.$verifier"

    @JvmSynthetic
    internal fun verifierBytes(): ByteArray = verifier.toByteArray(Charsets.US_ASCII)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ServiceCredentialSecret &&
            MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), other.value.toByteArray(Charsets.US_ASCII))

    override fun hashCode(): Int = credentialId.hashCode()

    override fun toString(): String = "ServiceCredentialSecret(****)"

    companion object {
        /** The number of random bytes in the verifier: 256 bits of entropy. */
        const val VERIFIER_BYTES: Int = 32

        private const val VERIFIER_LENGTH = 43
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private val format = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{$VERIFIER_LENGTH})")

        /**
         * The secret [value] represents, or `null` when it is not in the runtime's credential
         * format. A well-formed value is not necessarily a valid credential.
         */
        @JvmStatic
        fun parse(value: String): ServiceCredentialSecret? {
            val match = format.matchEntire(value) ?: return null
            val (id, verifier) = match.destructured
            // Only canonical encodings are accepted, so each secret has exactly one text form.
            val uuid = runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString() == id } ?: return null
            val bytes = decoder.decode(verifier)
            if (bytes.size != VERIFIER_BYTES || encoder.encodeToString(bytes) != verifier) return null
            return ServiceCredentialSecret(ServiceCredentialId(uuid), verifier)
        }

        @JvmSynthetic
        internal fun generate(
            credentialId: ServiceCredentialId,
            random: SecureRandom,
        ): ServiceCredentialSecret {
            val bytes = ByteArray(VERIFIER_BYTES)
            random.nextBytes(bytes)
            return ServiceCredentialSecret(credentialId, encoder.encodeToString(bytes))
        }
    }
}

/**
 * The result of creating a service credential: the persisted [credential] and its [secret].
 *
 * This is the only place the runtime ever hands out a credential secret. It cannot be
 * recovered later; a lost secret is replaced by creating another credential and revoking the
 * old one. [toString] redacts it.
 */
data class IssuedServiceCredential(
    val credential: ServiceCredential,
    val secret: ServiceCredentialSecret,
)
