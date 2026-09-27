package io.github.castab.commerce.runtime.session

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The opaque secret that authenticates one [PrincipalSession].
 *
 * commerce-runtime generates every token from [SecureRandom]: [BYTES] random bytes,
 * encoded as unpadded base64url, so it is safe in cookies, headers, and URLs. A token is
 * unrelated to the session's [SessionId] and to the principal's identity, and cannot be
 * derived from anything stored in the database, which holds only its SHA-256 digest.
 *
 * Instances exist only for well-formed values: a freshly issued token, or [parse] of text
 * a client presented. [value] is the secret itself; the application sends it to its client
 * and nowhere else. [toString] never reveals it, and equality compares in constant time.
 */
class SessionToken private constructor(
    /** The raw secret. Deliver it to the client; never persist or log it. */
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SessionToken &&
            MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), other.value.toByteArray(Charsets.US_ASCII))

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SessionToken(****)"

    companion object {
        /** The number of random bytes in a token: 256 bits of entropy. */
        const val BYTES: Int = 32

        private const val ENCODED_LENGTH = 43
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private val alphabet = Regex("[A-Za-z0-9_-]{$ENCODED_LENGTH}")

        /**
         * The token [value] represents, or `null` when it is not in the runtime's token
         * format. A well-formed value is not necessarily a valid session; only
         * [SessionManager.resolve] decides that.
         */
        @JvmStatic
        fun parse(value: String): SessionToken? {
            if (!alphabet.matches(value)) return null
            // Only the canonical encoding is accepted, so each secret has exactly one text form.
            val bytes = decoder.decode(value)
            if (bytes.size != BYTES || encoder.encodeToString(bytes) != value) return null
            return SessionToken(value)
        }

        @JvmSynthetic
        internal fun generate(random: SecureRandom): SessionToken {
            val bytes = ByteArray(BYTES)
            random.nextBytes(bytes)
            return SessionToken(encoder.encodeToString(bytes))
        }
    }
}

/**
 * The SHA-256 digest of a [SessionToken], the only form in which a token is stored or
 * looked up.
 *
 * A token is already 256 bits of uniformly random data, so a fast cryptographic digest is
 * sufficient; slow password hashing (bcrypt, Argon2, PBKDF2) adds nothing here. The digest
 * is internal so that no public API accepts or returns stored token material.
 */
internal class SessionTokenDigest private constructor(
    private val bytes: ByteArray,
) {
    fun bytes(): ByteArray = bytes.copyOf()

    /** Constant-time comparison. */
    override fun equals(other: Any?): Boolean = this === other || other is SessionTokenDigest && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SessionTokenDigest(****)"

    companion object {
        const val BYTES: Int = 32

        fun of(token: SessionToken): SessionTokenDigest =
            SessionTokenDigest(MessageDigest.getInstance("SHA-256").digest(token.value.toByteArray(Charsets.US_ASCII)))

        fun restore(bytes: ByteArray): SessionTokenDigest {
            check(bytes.size == BYTES) { "A stored session token digest must be $BYTES bytes" }
            return SessionTokenDigest(bytes.copyOf())
        }
    }
}
