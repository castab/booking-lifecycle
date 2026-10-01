package io.github.castab.commerce.runtime.serviceauth

import io.github.castab.commerce.staff.ServiceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The credential secret format and its Argon2id storage, without a database. */
class ServiceCredentialSecretSpec :
    FunSpec({
        val random = SecureRandom()

        fun secret(id: UUID = UUID.randomUUID()) = ServiceCredentialSecret.generate(ServiceCredentialId(id), random)

        context("secrets") {
            test("a secret is the credential id and 256 random bits, distinct every time") {
                val id = UUID.randomUUID()
                val first = secret(id)
                val second = secret(id)

                first.value shouldMatch Regex("${Regex.escape(id.toString())}\\.[A-Za-z0-9_-]{43}")
                first.credentialId shouldBe ServiceCredentialId(id)
                Base64.getUrlDecoder().decode(first.value.substringAfter('.')).size shouldBe ServiceCredentialSecret.VERIFIER_BYTES
                first shouldNotBe second
            }

            test("parse accepts exactly the canonical format") {
                val issued = secret()
                ServiceCredentialSecret.parse(issued.value) shouldBe issued

                val verifier = issued.value.substringAfter('.')
                val id = issued.credentialId.value.toString()
                listOf(
                    "",
                    verifier,
                    id,
                    "$id.",
                    ".$verifier",
                    "${id.uppercase()}.$verifier",
                    "$id.${verifier}A",
                    "$id.${verifier.dropLast(1)}",
                    "$id.$verifier.$verifier",
                    "$id:$verifier",
                    " ${issued.value}",
                    "not-a-uuid-0000-0000-0000-000000000000.$verifier",
                ).forEach { ServiceCredentialSecret.parse(it).shouldBeNull() }
                // The same 32 bytes with a non-canonical final character.
                val bytes = Base64.getUrlDecoder().decode(verifier)
                val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                ServiceCredentialSecret.parse("$id.$canonical").shouldNotBeNull()
                ServiceCredentialSecret.parse("$id.${canonical.dropLast(1)}B").shouldBeNull()
            }

            test("the secret never appears in text meant for logs") {
                val issued = secret()
                val credential = ServiceCredential(issued.credentialId, ServiceId(UUID.randomUUID()), "deploy", Instant.EPOCH, null)

                issued.toString() shouldBe "ServiceCredentialSecret(****)"
                IssuedServiceCredential(credential, issued).toString() shouldNotContain issued.value.substringAfter('.')
                IssuedServiceCredentialDto("id", "service", "deploy", "now", issued.value).toString() shouldNotContain issued.value
                ServiceAccessTokenRequestDto("service", issued.value).toString() shouldNotContain issued.value
            }

            test("credential labels and revocation times are validated") {
                val id = ServiceCredentialId(UUID.randomUUID())
                val service = ServiceId(UUID.randomUUID())
                shouldThrow<IllegalArgumentException> { ServiceCredential(id, service, " ", Instant.EPOCH, null) }
                shouldThrow<IllegalArgumentException> { ServiceCredential(id, service, "x".repeat(201), Instant.EPOCH, null) }
                shouldThrow<IllegalArgumentException> {
                    ServiceCredential(
                        id,
                        service,
                        "deploy",
                        Instant.EPOCH,
                        Instant.EPOCH.minusSeconds(1),
                    )
                }
                ServiceCredential(id, service, "deploy", Instant.EPOCH, null).active shouldBe true
                ServiceCredential(id, service, "deploy", Instant.EPOCH, Instant.EPOCH).active shouldBe false
            }
        }

        context("Argon2id hashes") {
            test("a hash is an Argon2id PHC string with the OWASP parameters and a fresh salt") {
                val issued = secret()
                val first = ServiceCredentialHash.of(issued, random).encoded()
                val second = ServiceCredentialHash.of(issued, random).encoded()

                first shouldMatch Regex("\\\$argon2id\\\$v=19\\\$m=19456,t=2,p=1\\\$[A-Za-z0-9+/]{22}\\\$[A-Za-z0-9+/]{43}")
                first shouldNotBe second
                first shouldNotContain issued.value.substringAfter('.')
                ServiceCredentialHash.of(issued, random).toString() shouldBe "ServiceCredentialHash(****)"
            }

            test("only the secret that produced a hash matches it") {
                val issued = secret()
                val stored = ServiceCredentialHash.restore(ServiceCredentialHash.of(issued, random).encoded())

                stored.matches(issued) shouldBe true
                stored.matches(ServiceCredentialSecret.parse(issued.value)!!) shouldBe true
                stored.matches(secret(issued.credentialId.value)) shouldBe false
            }

            test("a broken stored hash fails without echoing it") {
                listOf(
                    "plaintext",
                    "\$argon2i\$v=19\$m=19456,t=2,p=1\$c2FsdHNhbHRzYWx0c2FsdA\$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g",
                    "\$argon2id\$v=19\$m=99999999,t=2,p=1\$c2FsdHNhbHRzYWx0c2FsdA\$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g",
                    "\$argon2id\$v=19\$m=19456,t=0,p=1\$c2FsdHNhbHRzYWx0c2FsdA\$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g",
                ).forEach { encoded ->
                    shouldThrow<IllegalStateException> { ServiceCredentialHash.restore(encoded) }.message!! shouldNotContain encoded
                }
            }
        }
    })
