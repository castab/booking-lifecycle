package io.github.castab.commerce.runtime.serviceauth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The stored form of a [ServiceCredentialSecret]: an Argon2id hash of its verifier, encoded
 * as a PHC string (`$argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>`, unpadded
 * standard base64).
 *
 * New hashes use the OWASP password storage parameters for Argon2id (19 MiB, two
 * iterations, one lane) with a fresh 16-byte salt. The parameters are stored with each hash,
 * so they can be raised later without invalidating existing credentials. Argon2id comes from
 * Bouncy Castle; this type only encodes and compares.
 *
 * Internal so that no public API accepts or returns stored credential material.
 */
internal class ServiceCredentialHash private constructor(
    private val memoryKiB: Int,
    private val iterations: Int,
    private val parallelism: Int,
    private val salt: ByteArray,
    private val hash: ByteArray,
) {
    /** Whether [secret]'s verifier produces this hash. The comparison is constant-time. */
    fun matches(secret: ServiceCredentialSecret): Boolean =
        MessageDigest.isEqual(derive(secret.verifierBytes(), salt, memoryKiB, iterations, parallelism, hash.size), hash)

    fun encoded(): String =
        "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"

    override fun toString(): String = "ServiceCredentialHash(****)"

    companion object {
        const val MEMORY_KIB: Int = 19_456
        const val ITERATIONS: Int = 2
        const val PARALLELISM: Int = 1
        const val SALT_BYTES: Int = 16
        const val HASH_BYTES: Int = 32

        private val encoder = Base64.getEncoder().withoutPadding()
        private val decoder = Base64.getDecoder()
        private val phc = Regex("\\\$argon2id\\\$v=19\\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\\$([A-Za-z0-9+/]+)\\\$([A-Za-z0-9+/]+)")

        fun of(
            secret: ServiceCredentialSecret,
            random: SecureRandom,
        ): ServiceCredentialHash {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val hash = derive(secret.verifierBytes(), salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES)
            return ServiceCredentialHash(MEMORY_KIB, ITERATIONS, PARALLELISM, salt, hash)
        }

        /**
         * Rebuilds a stored hash. Unbounded or unknown parameters are a broken store, not a
         * caller mistake, and fail with [IllegalStateException]; the message never includes
         * the stored value.
         */
        fun restore(encoded: String): ServiceCredentialHash {
            val match = checkNotNull(phc.matchEntire(encoded)) { "A stored service credential hash is not an Argon2id PHC string" }
            val (memory, iterations, parallelism, salt, hash) = match.destructured
            val restored =
                ServiceCredentialHash(memory.toInt(), iterations.toInt(), parallelism.toInt(), decoder.decode(salt), decoder.decode(hash))
            check(
                restored.memoryKiB in 8 * restored.parallelism..1_048_576 &&
                    restored.iterations in 1..16 &&
                    restored.parallelism in 1..16 &&
                    restored.salt.size in 8..64 &&
                    restored.hash.size in 16..64,
            ) { "A stored service credential hash has unsupported Argon2id parameters" }
            return restored
        }

        private fun derive(
            verifier: ByteArray,
            salt: ByteArray,
            memoryKiB: Int,
            iterations: Int,
            parallelism: Int,
            length: Int,
        ): ByteArray {
            val parameters =
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memoryKiB)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build()
            val output = ByteArray(length)
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(verifier, output)
            return output
        }
    }
}
