package io.github.castab.commerce.runtime.session

import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Records the byte arrays it fills, proving which random source a token came from. */
private class RecordingSecureRandom : SecureRandom() {
    val requests = mutableListOf<ByteArray>()

    override fun nextBytes(bytes: ByteArray) {
        super.nextBytes(bytes)
        requests += bytes.copyOf()
    }
}

class SessionTokenSpec :
    FunSpec({
        val random = SecureRandom()

        context("tokens") {
            test("a token is 256 bits drawn from the supplied SecureRandom, encoded as unpadded base64url") {
                val source = RecordingSecureRandom()

                val token = SessionToken.generate(source)

                source.requests shouldHaveSize 1
                source.requests.single().size shouldBe SessionToken.BYTES
                Base64.getUrlDecoder().decode(token.value) shouldBe source.requests.single()
                token.value.length shouldBe 43
                token.value.matches(Regex("[A-Za-z0-9_-]+")) shouldBe true
            }

            test("generated tokens do not repeat") {
                val tokens = (1..10_000).map { SessionToken.generate(random).value }.toSet()

                tokens shouldHaveSize 10_000
            }

            test("parse accepts exactly the runtime's canonical token format") {
                val token = SessionToken.generate(random)

                SessionToken.parse(token.value) shouldBe token
                SessionToken.parse("").shouldBeNull()
                SessionToken.parse(token.value.dropLast(1)).shouldBeNull()
                SessionToken.parse(token.value + "A").shouldBeNull()
                SessionToken.parse(token.value.replaceRange(0, 1, "+")).shouldBeNull()
                SessionToken.parse("$token").shouldBeNull()
                // Same 32 bytes, but a non-canonical final character.
                val bytes = ByteArray(SessionToken.BYTES)
                val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                SessionToken.parse(canonical).shouldNotBeNull()
                SessionToken.parse(canonical.dropLast(1) + "B").shouldBeNull()
            }

            test("identifiers are never tokens") {
                val id = UUID.randomUUID()

                SessionToken.parse(id.toString()).shouldBeNull()
                SessionToken.parse(id.toString().replace("-", "")).shouldBeNull()
                SessionToken.parse(SessionId(id).toString()).shouldBeNull()
                SessionToken.parse(UserId(id).toString()).shouldBeNull()
                SessionToken.parse(ServiceId(id).toString()).shouldBeNull()
            }

            test("the raw secret never appears in text representations") {
                val token = SessionToken.generate(random)
                val now = Instant.parse("2026-01-01T00:00:00Z")
                val issued =
                    IssuedSession(
                        PrincipalSession(SessionId(UUID.randomUUID()), UserId(UUID.randomUUID()), now, now.plusSeconds(60), null),
                        token,
                    )

                token.toString() shouldBe "SessionToken(****)"
                issued.toString() shouldNotContain token.value
                SessionTokenDigest.of(token).toString() shouldNotContain token.value
            }

            test("equal tokens are equal and distinct tokens are not") {
                val token = SessionToken.generate(random)

                SessionToken.parse(token.value) shouldBe token
                SessionToken.parse(token.value).hashCode() shouldBe token.hashCode()
                SessionToken.generate(random) shouldNotBe token
            }
        }

        context("digests") {
            test("the stored digest is SHA-256 of the token text and never the token itself") {
                val token = SessionToken.generate(random)

                val digest = SessionTokenDigest.of(token).bytes()

                digest shouldBe MessageDigest.getInstance("SHA-256").digest(token.value.toByteArray(Charsets.US_ASCII))
                digest.size shouldBe SessionTokenDigest.BYTES
                String(digest, Charsets.ISO_8859_1) shouldNotContain token.value
            }

            test("digests compare by content and cannot be changed through their bytes") {
                val token = SessionToken.generate(random)
                val digest = SessionTokenDigest.of(token)

                digest.bytes().also { it.fill(0) }
                digest shouldBe SessionTokenDigest.of(token)
                digest shouldBe SessionTokenDigest.restore(digest.bytes())
                digest shouldNotBe SessionTokenDigest.of(SessionToken.generate(random))
                shouldThrow<IllegalStateException> { SessionTokenDigest.restore(ByteArray(16)) }
            }
        }

        context("sessions") {
            val createdAt = Instant.parse("2026-01-01T00:00:00Z")
            val expiresAt = createdAt.plusSeconds(3600)

            fun session(revokedAt: Instant? = null) =
                PrincipalSession(SessionId(UUID.randomUUID()), UserId(UUID.randomUUID()), createdAt, expiresAt, revokedAt)

            test("a session is active from creation until, but excluding, its expiry") {
                val session = session()

                session.isActive(createdAt.minusNanos(1)) shouldBe false
                session.isActive(createdAt) shouldBe true
                session.isActive(expiresAt.minusNanos(1)) shouldBe true
                session.isActive(expiresAt) shouldBe false
            }

            test("a revoked session is inactive from its revocation onwards") {
                val revokedAt = createdAt.plusSeconds(60)
                val session = session(revokedAt)

                session.isActive(revokedAt.minusNanos(1)) shouldBe true
                session.isActive(revokedAt) shouldBe false
                session.isActive(expiresAt.minusNanos(1)) shouldBe false
            }

            test("expiry must follow creation, and revocation cannot precede it") {
                shouldThrow<IllegalArgumentException> {
                    PrincipalSession(SessionId(UUID.randomUUID()), UserId(UUID.randomUUID()), createdAt, createdAt, null)
                }
                shouldThrow<IllegalArgumentException> { session(createdAt.minusSeconds(1)) }
            }
        }
    })
