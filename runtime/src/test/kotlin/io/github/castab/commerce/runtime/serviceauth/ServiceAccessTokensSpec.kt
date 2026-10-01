package io.github.castab.commerce.runtime.serviceauth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.ServiceCredentialRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.MutableClock
import io.github.castab.commerce.runtime.testing.TEST_SERVICE_TOKEN_KEY_BYTES
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.capturingStandardOutput
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.insertTestPrincipal
import io.github.castab.commerce.runtime.testing.strings
import io.github.castab.commerce.runtime.testing.testServiceTokens
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Jdbi
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Service credentials and service access tokens over the real store, with a hand-driven
 * clock. Tokens are forged with Nimbus directly to prove what verification rejects.
 */
class ServiceAccessTokensSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var transactor: Transactor
        lateinit var credentials: PersistentServiceCredentials
        lateinit var tokens: SignedServiceAccessTokens
        val clock = MutableClock(Instant.parse("2026-10-01T12:00:00Z"))
        val configuration = testServiceTokens(lifetimeMinutes = 15)
        val principals = AuthorizationRepository()

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "service-access-tokens-spec")
            MigrationLifecycle(dataSource).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
            credentials = PersistentServiceCredentials(transactor, ServiceCredentialRepository(), principals, clock)
            tokens = SignedServiceAccessTokens(configuration, credentials, transactor, principals, clock)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun persistedService(status: PrincipalStatus = PrincipalStatus.ACTIVE) =
            ServiceId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it, status) }

        fun setStatus(
            service: ServiceId,
            status: PrincipalStatus,
        ) = transactor.inTransaction { principals.updateStatus(it, service, status) }

        fun issue(service: ServiceId) = tokens.issue(service, credentials.create(service, "deploy").secret).shouldNotBeNull()

        fun claims(
            service: ServiceId,
            build: JWTClaimsSet.Builder.() -> Unit = {},
        ): JWTClaimsSet =
            JWTClaimsSet
                .Builder()
                .issuer(configuration.issuer)
                .audience(configuration.issuer)
                .subject(service.value.toString())
                .claim("principal_kind", "SERVICE")
                .issueTime(Date.from(clock.now))
                .expirationTime(Date.from(clock.now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                .apply(build)
                .build()

        fun forge(
            claims: JWTClaimsSet,
            key: ByteArray = TEST_SERVICE_TOKEN_KEY_BYTES,
            algorithm: JWSAlgorithm = JWSAlgorithm.HS256,
            type: JOSEObjectType? = SignedServiceAccessTokens.TYPE,
        ): ServiceAccessToken {
            val jwt = SignedJWT(JWSHeader.Builder(algorithm).type(type).build(), claims)
            jwt.sign(MACSigner(key))
            return ServiceAccessToken.parse(jwt.serialize())!!
        }

        context("credential lifecycle") {
            test("a credential is created active, and its secret is returned only on creation") {
                val service = persistedService()
                clock.advance(Duration.ofSeconds(1))

                val issued = credentials.create(service, "  production  ")

                issued.credential.serviceId shouldBe service
                issued.credential.label shouldBe "production"
                issued.credential.createdAt shouldBe clock.now
                issued.credential.active shouldBe true
                issued.secret.credentialId shouldBe issued.credential.id
                credentials.list(service) shouldContainExactly listOf(issued.credential)
            }

            test("the stored row holds an Argon2id hash, never the secret") {
                val service = persistedService()
                val issued = credentials.create(service, "deploy")
                val verifier = issued.secret.value.substringAfter('.')

                val row =
                    dataSource
                        .strings("SELECT row_to_json(c)::text FROM commerce.service_credentials c WHERE principal_id = '${service.value}'")
                        .single()
                row shouldNotContain verifier
                row shouldContain "\$argon2id\$v=19\$m=19456,t=2,p=1\$"
                dataSource
                    .strings(
                        "SELECT count(*) FROM commerce.service_credentials WHERE position('$verifier' IN secret_hash) > 0",
                    ).single() shouldBe "0"
            }

            test("a service holds several active credentials, and revoking one leaves the other working") {
                val service = persistedService()
                val first = credentials.create(service, "credential A")
                clock.advance(Duration.ofSeconds(1))
                val second = credentials.create(service, "credential B")

                credentials.list(service).map { it.id } shouldContainExactly listOf(first.credential.id, second.credential.id)
                tokens.issue(service, first.secret).shouldNotBeNull()
                tokens.issue(service, second.secret).shouldNotBeNull()

                clock.advance(Duration.ofSeconds(1))
                val revoked = credentials.revoke(service, first.credential.id)

                revoked.revokedAt shouldBe clock.now
                revoked.active shouldBe false
                tokens.issue(service, first.secret).shouldBeNull()
                tokens.issue(service, second.secret).shouldNotBeNull()
                credentials.list(service).map { it.active } shouldContainExactly listOf(false, true)
            }

            test("revocation is idempotent and limited to the credential's own service") {
                val service = persistedService()
                val other = persistedService()
                val issued = credentials.create(service, "deploy")

                shouldThrow<CommerceFailure.NotFound> { credentials.revoke(other, issued.credential.id) }
                shouldThrow<CommerceFailure.NotFound> { credentials.revoke(service, ServiceCredentialId(UUID.randomUUID())) }
                credentials.list(service).single().active shouldBe true

                val revoked = credentials.revoke(service, issued.credential.id)
                clock.advance(Duration.ofSeconds(5))
                credentials.revoke(service, issued.credential.id) shouldBe revoked
            }

            test("only existing services have credentials; a user principal can never hold one") {
                val user = UserId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it) }

                shouldThrow<CommerceFailure.NotFound> { credentials.create(ServiceId(user.value), "deploy") }
                shouldThrow<CommerceFailure.NotFound> { credentials.create(ServiceId(UUID.randomUUID()), "deploy") }
                shouldThrow<CommerceFailure.NotFound> { credentials.list(ServiceId(user.value)) }
                // The schema refuses a USER credential even when inserted directly.
                shouldThrow<Exception> {
                    dataSource.execute(
                        "INSERT INTO commerce.service_credentials " +
                            "(credential_id, principal_kind, principal_id, label, secret_hash, created_at) " +
                            "VALUES ('${UUID.randomUUID()}', 'USER', '${user.value}', 'x', '\$argon2id\$x', now())",
                    )
                }
            }

            test("labels are required and bounded") {
                val service = persistedService()
                shouldThrow<CommerceFailure.ValidationFailed> { credentials.create(service, "   ") }
                shouldThrow<CommerceFailure.ValidationFailed> { credentials.create(service, "x".repeat(201)) }
                credentials.list(service) shouldBe emptyList()
            }

            test("credential writes join the caller's transaction") {
                val service = persistedService()
                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { transaction ->
                        credentials.create(transaction, service, "rolled back")
                        error("roll back")
                    }
                }
                credentials.list(service) shouldBe emptyList()
            }
        }

        context("issuing access tokens") {
            test("a valid service id and secret yield a token for the service that expires after the configured lifetime") {
                val service = persistedService()

                val issued = issue(service)

                issued.serviceId shouldBe service
                issued.issuedAt shouldBe clock.now
                issued.expiresAt shouldBe clock.now.plus(Duration.ofMinutes(15))
                tokens.resolve(issued.token) shouldBe service
                issued.toString() shouldNotContain issued.token.value
            }

            test("every authentication failure is the same null") {
                val service = persistedService()
                val other = persistedService()
                val issued = credentials.create(service, "deploy")
                val foreign = credentials.create(other, "deploy")
                val user = UserId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it) }

                // Unknown service, wrong secret, unknown credential, another service's credential, a user's id.
                tokens.issue(ServiceId(UUID.randomUUID()), issued.secret).shouldBeNull()
                tokens.issue(service, ServiceCredentialSecret.generate(issued.credential.id, java.security.SecureRandom())).shouldBeNull()
                tokens
                    .issue(
                        service,
                        ServiceCredentialSecret.generate(ServiceCredentialId(UUID.randomUUID()), java.security.SecureRandom()),
                    ).shouldBeNull()
                tokens.issue(service, foreign.secret).shouldBeNull()
                tokens.issue(ServiceId(user.value), issued.secret).shouldBeNull()
                tokens.issue(service, issued.secret).shouldNotBeNull()
            }

            test("a disabled service cannot obtain a token, and can again once activated") {
                val service = persistedService()
                val issued = credentials.create(service, "deploy")

                setStatus(service, PrincipalStatus.DISABLED)
                tokens.issue(service, issued.secret).shouldBeNull()
                setStatus(service, PrincipalStatus.ACTIVE)
                tokens.issue(service, issued.secret).shouldNotBeNull()
            }

            test("the token carries identity claims only: no secret, roles, or permissions") {
                val service = persistedService()
                val issued = credentials.create(service, "deploy")
                val token = tokens.issue(service, issued.secret)!!.token.value

                val header = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.substringBefore('.')))).jsonObject
                val payload =
                    Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).jsonObject

                header.keys shouldContainExactlyInAnyOrder setOf("alg", "typ")
                header["alg"]!!.jsonPrimitive.content shouldBe "HS256"
                header["typ"]!!.jsonPrimitive.content shouldBe "commerce-service-access+jwt"
                payload.keys shouldContainExactlyInAnyOrder setOf("iss", "aud", "sub", "principal_kind", "iat", "exp", "jti")
                payload["sub"]!!.jsonPrimitive.content shouldBe service.value.toString()
                payload["principal_kind"]!!.jsonPrimitive.content shouldBe "SERVICE"
                token shouldNotContain issued.secret.value.substringAfter('.')
            }

            test("authentication logs a reason for failures and never the secret or token") {
                val service = persistedService()
                val issued = credentials.create(service, "deploy")
                var token = ""

                val output =
                    capturingStandardOutput {
                        tokens.issue(service, ServiceCredentialSecret.generate(issued.credential.id, java.security.SecureRandom()))
                        credentials.revoke(service, issued.credential.id)
                        tokens.issue(service, issued.secret)
                        token = issue(service).token.value
                    }

                output shouldContain "event=service_authentication_failed service=${service.value}"
                output shouldContain "reason=secret_mismatch"
                output shouldContain "reason=credential_revoked"
                output shouldContain "event=service_access_token_issued"
                output shouldNotContain issued.secret.value.substringAfter('.')
                output shouldNotContain token
                output shouldNotContain token.substringAfterLast('.')
            }
        }

        context("verifying access tokens") {
            test("a token stops authenticating when it expires") {
                val service = persistedService()
                val issued = issue(service)
                val start = clock.now

                clock.advance(Duration.ofMinutes(15).minusSeconds(1))
                tokens.resolve(issued.token) shouldBe service
                clock.advance(Duration.ofSeconds(1))
                tokens.resolve(issued.token).shouldBeNull()

                clock.now = start
            }

            test("malformed text is not a token") {
                listOf("", "abc", "a.b", "a.b.c.d", "a..c", "a.b.", "${"a".repeat(4097)}.b.c", "not a token")
                    .forEach { ServiceAccessToken.parse(it).shouldBeNull() }
                tokens.resolve(ServiceAccessToken.parse("a.b.c")!!).shouldBeNull()
            }

            test("a modified token is rejected") {
                val service = persistedService()
                val other = persistedService()
                val token = issue(service).token.value
                val (header, payload, signature) = token.split('.')
                val otherPayload =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        String(Base64.getUrlDecoder().decode(payload))
                            .replace(service.value.toString(), other.value.toString())
                            .toByteArray(),
                    )
                val flipped = signature.dropLast(2) + (if (signature.takeLast(2) == "AA") "BA" else "AA")

                tokens.resolve(ServiceAccessToken.parse("$header.$otherPayload.$signature")!!).shouldBeNull()
                tokens.resolve(ServiceAccessToken.parse("$header.$payload.$flipped")!!).shouldBeNull()
                tokens.resolve(ServiceAccessToken.parse(token)!!) shouldBe service
            }

            test("a token signed with another key, another algorithm, or no signature is rejected") {
                val service = persistedService()
                tokens.resolve(forge(claims(service))) shouldBe service

                tokens.resolve(forge(claims(service), key = ByteArray(32) { 42 })).shouldBeNull()
                tokens
                    .resolve(
                        forge(claims(service), key = TEST_SERVICE_TOKEN_KEY_BYTES + TEST_SERVICE_TOKEN_KEY_BYTES, JWSAlgorithm.HS512),
                    ).shouldBeNull()
                val unsigned = PlainJWT(claims(service)).serialize()
                ServiceAccessToken.parse(unsigned).shouldBeNull()
                // An "alg: none" header with a fabricated signature segment.
                val none = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
                tokens.resolve(ServiceAccessToken.parse("$none.${unsigned.split('.')[1]}.AAAA")!!).shouldBeNull()
            }

            test("the type, issuer, audience, principal kind, and every required claim are enforced") {
                val service = persistedService()

                tokens.resolve(forge(claims(service), type = null)).shouldBeNull()
                tokens.resolve(forge(claims(service), type = JOSEObjectType.JWT)).shouldBeNull()
                tokens.resolve(forge(claims(service) { issuer("another-runtime") })).shouldBeNull()
                tokens.resolve(forge(claims(service) { audience("another-runtime") })).shouldBeNull()
                tokens.resolve(forge(claims(service) { audience(null as String?) })).shouldBeNull()
                tokens.resolve(forge(claims(service) { claim("principal_kind", "USER") })).shouldBeNull()
                tokens.resolve(forge(claims(service) { claim("principal_kind", null) })).shouldBeNull()
                tokens.resolve(forge(claims(service) { subject("not-a-uuid") })).shouldBeNull()
                tokens.resolve(forge(claims(service) { jwtID(null) })).shouldBeNull()
                tokens.resolve(forge(claims(service) { issueTime(null) })).shouldBeNull()
                tokens.resolve(forge(claims(service) { expirationTime(null) })).shouldBeNull()
                tokens.resolve(forge(claims(service) { expirationTime(Date.from(clock.now)) })).shouldBeNull()
            }

            test("a token resolves only while its service exists and is active") {
                val service = persistedService()
                val token = issue(service).token

                setStatus(service, PrincipalStatus.DISABLED)
                tokens.resolve(token).shouldBeNull()
                setStatus(service, PrincipalStatus.ACTIVE)
                tokens.resolve(token) shouldBe service
                // A validly signed token for a principal that does not exist.
                tokens.resolve(forge(claims(ServiceId(UUID.randomUUID())))).shouldBeNull()
                // A user with the token's UUID is a different principal.
                val user = UserId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it) }
                tokens.resolve(forge(claims(ServiceId(user.value)))).shouldBeNull()
            }

            test("revoking the credential does not cut short a token it already obtained") {
                val service = persistedService()
                val issued = credentials.create(service, "deploy")
                val token = tokens.issue(service, issued.secret)!!.token

                credentials.revoke(service, issued.credential.id)

                tokens.issue(service, issued.secret).shouldBeNull()
                tokens.resolve(token) shouldBe service
            }

            test("an unvalidated configuration still cannot weaken the key or lengthen the lifetime") {
                fun tokens(configuration: io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.ServiceTokens) =
                    SignedServiceAccessTokens(configuration, credentials, transactor, principals, clock)

                shouldThrow<IllegalArgumentException> { tokens(testServiceTokens(lifetimeMinutes = 61)) }
                shouldThrow<IllegalArgumentException> { tokens(testServiceTokens(issuer = " ")) }
                shouldThrow<IllegalArgumentException> {
                    tokens(testServiceTokens().copy(signingKey = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })))
                }
            }

            test("a runtime configured with another issuer rejects this runtime's tokens even with the same key") {
                val service = persistedService()
                val token = issue(service).token
                val elsewhere =
                    SignedServiceAccessTokens(testServiceTokens(issuer = "another-runtime"), credentials, transactor, principals, clock)

                elsewhere.resolve(token).shouldBeNull()
                tokens.resolve(token) shouldBe service
            }
        }
    })
