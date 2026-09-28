package io.github.castab.commerce.runtime.session

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresPrincipalSessionRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.MutableClock
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.capturingStandardOutput
import io.github.castab.commerce.runtime.testing.insertTestPrincipal
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jdbi.v3.core.Jdbi
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The session lifecycle against a real PostgreSQL. A hand-driven clock set decades away
 * from the database clock proves every expiry decision uses the runtime's clock alone.
 */
class SessionManagerSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var transactor: Transactor
        lateinit var sessions: SessionManager
        val lifetime = Duration.ofHours(12)
        val clock = MutableClock(Instant.parse("2071-03-04T05:06:07.123456789Z"))

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "session-manager-spec")
            MigrationLifecycle(dataSource, listOf("classpath:db/testapp")).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
            sessions =
                PersistentSessionManager(transactor, PostgresPrincipalSessionRepository(), AuthorizationRepository(), lifetime, clock)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun user() = UserId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it) }

        test("unknown and disabled users and services cannot receive sessions") {
            val unknownUser = UserId(UUID.randomUUID())
            val unknownService = ServiceId(UUID.randomUUID())
            shouldThrow<CommerceFailure.NotFound> { sessions.create(unknownUser) }
            shouldThrow<CommerceFailure.NotFound> { sessions.create(unknownService) }

            val disabledUser = UserId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it, PrincipalStatus.DISABLED) }
            val disabledService = ServiceId(UUID.randomUUID()).also { transactor.insertTestPrincipal(it, PrincipalStatus.DISABLED) }
            shouldThrow<CommerceFailure.Conflict> { sessions.create(disabledUser) }
            shouldThrow<CommerceFailure.Conflict> { sessions.create(disabledService) }
        }

        test("resolution rejects a principal disabled outside the directory's revoke operation") {
            val principal = user()
            val token = sessions.create(principal).token
            transactor.inTransaction { AuthorizationRepository().updateStatus(it, principal, PrincipalStatus.DISABLED) }
            sessions.resolve(token).shouldBeNull()
        }

        test("resolution rejects a session whose principal no longer exists") {
            val principal = user()
            val token = sessions.create(principal).token
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createUpdate("DELETE FROM commerce.users WHERE principal_id = :id")
                    .bind("id", principal.value)
                    .execute()
                transaction.handle
                    .createUpdate("DELETE FROM commerce.principals WHERE principal_kind = 'USER' AND principal_id = :id")
                    .bind("id", principal.value)
                    .execute()
            }
            sessions.resolve(token).shouldBeNull()
        }

        test("create issues a fixed-lifetime session for an already authenticated principal") {
            val principal = user()

            val issued = sessions.create(principal)

            issued.session.principalId shouldBe principal
            issued.session.createdAt shouldBe Instant.parse("2071-03-04T05:06:07.123456Z")
            issued.session.expiresAt shouldBe issued.session.createdAt.plus(lifetime)
            issued.session.revokedAt.shouldBeNull()
            sessions.resolve(issued.token) shouldBe principal
        }

        test("every session gets its own token and identity") {
            val principal = user()

            val first = sessions.create(principal)
            val second = sessions.create(principal)

            first.token shouldNotBe second.token
            first.session.id shouldNotBe second.session.id
        }

        test("a service principal's session resolves to its ServiceId") {
            val service = ServiceId(UUID.randomUUID())
            transactor.insertTestPrincipal(service)

            sessions.resolve(sessions.create(service).token) shouldBe service
        }

        test("an unknown token resolves to nothing") {
            sessions.resolve(SessionToken.generate(java.security.SecureRandom())).shouldBeNull()
        }

        test("a session expires at the end of its lifetime, and resolving never extends it") {
            val issued = sessions.create(user())
            val start = clock.now

            clock.advance(lifetime.minusSeconds(1))
            sessions.resolve(issued.token) shouldBe issued.session.principalId
            clock.advance(Duration.ofSeconds(1))
            sessions.resolve(issued.token).shouldBeNull()

            clock.now = start
        }

        test("revoke makes a session unusable immediately, is idempotent, and leaves other sessions alone") {
            val principal = user()
            val revoked = sessions.create(principal)
            val sibling = sessions.create(principal)
            val unrelated = sessions.create(user())

            sessions.revoke(revoked.token)
            sessions.revoke(revoked.token)
            sessions.revoke(SessionToken.generate(java.security.SecureRandom()))

            sessions.resolve(revoked.token).shouldBeNull()
            sessions.resolve(sibling.token) shouldBe principal
            sessions.resolve(unrelated.token) shouldBe unrelated.session.principalId
        }

        test("revokeAll invalidates every session of one principal and no other principal's") {
            val principal = user()
            val first = sessions.create(principal)
            val second = sessions.create(principal)
            val otherUser = sessions.create(user())
            // Same UUID, different kind of principal.
            val sameUuidId = ServiceId(principal.value).also { transactor.insertTestPrincipal(it) }
            val sameUuidService = sessions.create(sameUuidId)

            sessions.revokeAll(principal)
            sessions.revokeAll(principal)

            sessions.resolve(first.token).shouldBeNull()
            sessions.resolve(second.token).shouldBeNull()
            sessions.resolve(otherUser.token) shouldBe otherUser.session.principalId
            sessions.resolve(sameUuidService.token) shouldBe ServiceId(principal.value)

            // A session created afterwards is unaffected: revokeAll is not a standing ban.
            val later = sessions.create(principal)
            sessions.resolve(later.token) shouldBe principal
        }

        test("the transaction overloads commit and roll back with the caller's transaction") {
            val principal = user()
            val kept = sessions.create(principal)

            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    sessions.create(transaction, principal)
                    sessions.revokeAll(transaction, principal)
                    error("the application's own write failed")
                }
            }
            sessions.resolve(kept.token) shouldBe principal

            val issued = transactor.inTransaction { transaction -> sessions.create(transaction, principal) }
            sessions.resolve(issued.token) shouldBe principal
            transactor.inTransaction { transaction -> sessions.revoke(transaction, issued.token) }
            sessions.resolve(issued.token).shouldBeNull()
        }

        test("session identifiers and principal identifiers are not tokens") {
            val principal = user()
            val issued = sessions.create(principal)

            SessionToken
                .parse(
                    issued.session.id.value
                        .toString(),
                ).shouldBeNull()
            SessionToken.parse(principal.value.toString()).shouldBeNull()
        }

        test("session lifecycle logs name the session and principal but never the token") {
            lateinit var issued: IssuedSession
            val output =
                capturingStandardOutput {
                    issued = sessions.create(user())
                    sessions.resolve(issued.token)
                    sessions.revoke(issued.token)
                    sessions.revokeAll(issued.session.principalId)
                }

            output shouldContain "event=session_created session=${issued.session.id.value}"
            output shouldContain "event=session_revoked"
            output shouldContain "event=sessions_revoked"
            output shouldNotContain issued.token.value
        }

        test("the lifetime must be positive") {
            shouldThrow<IllegalArgumentException> {
                PersistentSessionManager(transactor, PostgresPrincipalSessionRepository(), AuthorizationRepository(), Duration.ZERO, clock)
            }
        }
    })
