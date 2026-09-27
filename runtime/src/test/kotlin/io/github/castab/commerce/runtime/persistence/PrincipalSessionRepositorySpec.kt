package io.github.castab.commerce.runtime.persistence

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.PrincipalSession
import io.github.castab.commerce.runtime.session.SessionId
import io.github.castab.commerce.runtime.session.SessionToken
import io.github.castab.commerce.runtime.session.SessionTokenDigest
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jdbi.v3.core.Jdbi
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `commerce.principal_sessions` against a real PostgreSQL with the real migrations: schema,
 * round trips of every principal kind, digest lookup and uniqueness, revocation, and
 * participation in caller-owned transactions.
 */
class PrincipalSessionRepositorySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var jdbi: Jdbi
        lateinit var transactor: Transactor
        val repository: PrincipalSessionRepository = PostgresPrincipalSessionRepository()
        val random = SecureRandom()
        // Far from the database clock, so nothing can pass by reading it.
        val createdAt = Instant.parse("2031-05-06T07:08:09.123456Z")

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "principal-session-repository-spec")
            MigrationLifecycle(dataSource, listOf("classpath:db/testapp")).migrate()
            jdbi = Jdbi.create(dataSource)
            transactor = Transactor(jdbi)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun <T> query(
            sql: String,
            type: Class<T>,
        ): List<T> = jdbi.withHandle<List<T>, Exception> { it.createQuery(sql).mapTo(type).list() }

        fun session(
            principalId: PrincipalId = UserId(UUID.randomUUID()),
            lifetime: Duration = Duration.ofHours(1),
        ) = PrincipalSession(SessionId(UUID.randomUUID()), principalId, createdAt, createdAt.plus(lifetime), revokedAt = null)

        fun stored(session: PrincipalSession = session()): Pair<PrincipalSession, SessionToken> {
            val token = SessionToken.generate(random)
            transactor.inTransaction { repository.insert(it, session, SessionTokenDigest.of(token)) }
            return session to token
        }

        fun find(token: SessionToken) = transactor.inTransaction { repository.findByDigest(it, SessionTokenDigest.of(token)) }

        test("the migration creates the session table, its constraints, and its indexes") {
            query(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'commerce' AND table_name = 'principal_sessions'",
                String::class.java,
            ) shouldContainExactlyInAnyOrder
                listOf("session_id", "principal_kind", "principal_id", "token_digest", "created_at", "expires_at", "revoked_at")
            query(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'commerce' AND tablename = 'principal_sessions'",
                String::class.java,
            ) shouldContainExactlyInAnyOrder
                listOf(
                    "principal_sessions_pkey",
                    "principal_sessions_token_digest_unique",
                    "principal_sessions_principal",
                    "principal_sessions_expires_at",
                )
        }

        test("user and service sessions round trip with their exact principal kind and timestamps") {
            val (user, userToken) = stored(session(UserId(UUID.randomUUID())))
            val (service, serviceToken) = stored(session(ServiceId(UUID.randomUUID())))

            find(userToken) shouldBe user
            find(serviceToken) shouldBe service
            (find(serviceToken)!!.principalId is ServiceId) shouldBe true
        }

        test("principals persist as an explicit, constrained set of kinds") {
            val (user, _) = stored(session(UserId(UUID.randomUUID())))
            val (service, _) = stored(session(ServiceId(UUID.randomUUID())))

            fun storedKind(id: SessionId): String =
                jdbi.withHandle<String, Exception> { handle ->
                    handle
                        .createQuery("SELECT principal_kind FROM commerce.principal_sessions WHERE session_id = :id")
                        .bind("id", id.value)
                        .mapTo(String::class.java)
                        .one()
                }
            storedKind(user.id) shouldBe "USER"
            storedKind(service.id) shouldBe "SERVICE"

            // A kind the runtime has not deliberately added cannot be stored.
            shouldThrow<org.jdbi.v3.core.statement.UnableToExecuteStatementException> {
                jdbi.useHandle<Exception> { handle ->
                    handle
                        .createUpdate(
                            """INSERT INTO commerce.principal_sessions
                               (session_id, principal_kind, principal_id, token_digest, created_at, expires_at)
                               VALUES (:id, 'ROBOT', :principal, :digest, now(), now() + interval '1 hour')""",
                        ).bind("id", UUID.randomUUID())
                        .bind("principal", UUID.randomUUID())
                        .bind("digest", SessionTokenDigest.of(SessionToken.generate(random)).bytes())
                        .execute()
                }
            }.message shouldContain "principal_sessions_principal_kind"
        }

        test("a user and a service with the same UUID are different principals") {
            val id = UUID.randomUUID()
            val (_, userToken) = stored(session(UserId(id)))
            val (_, serviceToken) = stored(session(ServiceId(id)))

            find(userToken)!!.principalId shouldBe UserId(id)
            find(serviceToken)!!.principalId shouldBe ServiceId(id)
            transactor.inTransaction { repository.revokeAll(it, UserId(id), createdAt.plusSeconds(1)) } shouldBe 1
            find(serviceToken)!!.revokedAt.shouldBeNull()
        }

        test("the database holds only a SHA-256 digest of the token, never the token") {
            val (session, token) = stored()

            val rows =
                jdbi.withHandle<List<String>, Exception> { handle ->
                    handle
                        .createQuery("SELECT s::text FROM commerce.principal_sessions s WHERE session_id = :id")
                        .bind("id", session.id.value)
                        .mapTo(String::class.java)
                        .list()
                }
            rows.single() shouldNotContain token.value
            jdbi.withHandle<Int, Exception> { handle ->
                handle
                    .createQuery(
                        "SELECT count(*) FROM commerce.principal_sessions " +
                            "WHERE session_id = :id AND token_digest = sha256(convert_to(:token, 'UTF8'))",
                    ).bind("id", session.id.value)
                    .bind("token", token.value)
                    .mapTo(Int::class.java)
                    .one()
            } shouldBe 1
        }

        test("an unknown digest finds nothing") {
            find(SessionToken.generate(random)).shouldBeNull()
        }

        test("a token digest is unique") {
            val (_, token) = stored()

            shouldThrow<CommerceFailure.Conflict> {
                transactor.inTransaction { repository.insert(it, session(), SessionTokenDigest.of(token)) }
            }.message shouldNotContain token.value
        }

        test("revocation persists, applies only to active sessions, and keeps the first revocation time") {
            val (_, token) = stored()
            val (other, otherToken) = stored()
            val revokedAt = createdAt.plusSeconds(10)

            transactor.inTransaction { repository.revoke(it, SessionTokenDigest.of(token), revokedAt) } shouldBe true
            transactor.inTransaction { repository.revoke(it, SessionTokenDigest.of(token), revokedAt.plusSeconds(1)) } shouldBe false

            find(token)!!.revokedAt shouldBe revokedAt
            find(otherToken) shouldBe other
            val (_, expiredToken) = stored(session(lifetime = Duration.ofSeconds(5)))
            transactor.inTransaction { repository.revoke(it, SessionTokenDigest.of(expiredToken), revokedAt) } shouldBe false
            find(expiredToken)!!.revokedAt.shouldBeNull()
        }

        test("revoking all sessions of a principal persists for that principal's active sessions only") {
            val principal = UserId(UUID.randomUUID())
            val (_, first) = stored(session(principal))
            val (_, second) = stored(session(principal))
            val (_, expired) = stored(session(principal, lifetime = Duration.ofSeconds(5)))
            val (other, otherToken) = stored(session(UserId(UUID.randomUUID())))
            val revokedAt = createdAt.plusSeconds(10)

            transactor.inTransaction { repository.revokeAll(it, principal, revokedAt) } shouldBe 2
            transactor.inTransaction { repository.revokeAll(it, principal, revokedAt) } shouldBe 0

            find(first)!!.revokedAt shouldBe revokedAt
            find(second)!!.revokedAt shouldBe revokedAt
            find(expired)!!.revokedAt.shouldBeNull()
            find(otherToken) shouldBe other
        }

        test("sessions commit and roll back with the caller's transaction, together with application rows") {
            val rolledBack = SessionToken.generate(random)
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    repository.insert(transaction, session(), SessionTokenDigest.of(rolledBack))
                    transaction.handle
                        .createUpdate("INSERT INTO public.test_application_records (id, value) VALUES (:id, 'rolled back')")
                        .bind("id", UUID.randomUUID())
                        .execute()
                    throw IllegalStateException("roll back both")
                }
            }
            find(rolledBack).shouldBeNull()

            val committed = SessionToken.generate(random)
            val recordId = UUID.randomUUID()
            transactor.inTransaction { transaction ->
                repository.insert(transaction, session(), SessionTokenDigest.of(committed))
                transaction.handle
                    .createUpdate("INSERT INTO public.test_application_records (id, value) VALUES (:id, 'committed')")
                    .bind("id", recordId)
                    .execute()
                // Visible inside the same transaction before it commits.
                repository.findByDigest(transaction, SessionTokenDigest.of(committed)).shouldNotBeNull()
            }
            find(committed)!!.createdAt shouldBe createdAt
            query("SELECT count(*) FROM public.test_application_records WHERE value = 'committed'", Int::class.java).single() shouldBe 1

            // A revocation rolled back with its caller's transaction leaves the session active.
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    repository.revoke(transaction, SessionTokenDigest.of(committed), createdAt.plusSeconds(1)) shouldBe true
                    throw IllegalStateException("roll back the revocation")
                }
            }
            find(committed)!!.revokedAt.shouldBeNull()
        }
    })
