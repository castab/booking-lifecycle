package io.github.castab.commerce.runtime.session

import io.github.castab.commerce.runtime.persistence.PrincipalIdColumns
import io.github.castab.commerce.runtime.persistence.PrincipalSessionRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * The lifecycle of authenticated principal sessions, available to applications as
 * `CommerceRuntimeContext.sessions`.
 *
 * Sessions begin after identity has been proven. The application verifies its own
 * credentials (passwords, OAuth, passkeys, ...; none of which the runtime knows about),
 * obtains a [PrincipalId], and calls [create]. The runtime then owns the session: token
 * generation, digest storage, expiry, resolution, and revocation.
 *
 * Methods without a [Transaction] run in their own transaction. The overloads that take
 * one join the caller's transaction, so a session change commits or rolls back together
 * with the application's own writes, for example disabling a principal and revoking all
 * of its sessions.
 */
interface SessionManager {
    /**
     * Starts a session for [principalId], which the application has already authenticated.
     * The session expires after the configured lifetime. The returned token is the only
     * copy of the secret; only its digest is stored.
     */
    fun create(principalId: PrincipalId): IssuedSession

    /** [create] inside the caller's [transaction]. */
    fun create(
        transaction: Transaction,
        principalId: PrincipalId,
    ): IssuedSession

    /**
     * The principal [token] authenticates, or `null` when there is no such session, or it
     * has expired or been revoked. Resolution never extends a session.
     */
    fun resolve(token: SessionToken): PrincipalId?

    /** Makes the session of [token] unusable immediately. Idempotent: an unknown, expired, or revoked token is ignored. */
    fun revoke(token: SessionToken)

    /** [revoke] inside the caller's [transaction]. */
    fun revoke(
        transaction: Transaction,
        token: SessionToken,
    )

    /** Revokes every active session of [principalId], and no other principal's. Idempotent. */
    fun revokeAll(principalId: PrincipalId)

    /** [revokeAll] inside the caller's [transaction]. */
    fun revokeAll(
        transaction: Transaction,
        principalId: PrincipalId,
    )
}

/**
 * The runtime's [SessionManager] over PostgreSQL. [clock] is the single time source for
 * creation, expiry, and revocation; timestamps are truncated to microseconds, the
 * precision PostgreSQL stores.
 */
internal class PersistentSessionManager(
    private val transactor: Transactor,
    private val repository: PrincipalSessionRepository,
    private val lifetime: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : SessionManager {
    init {
        require(!lifetime.isNegative && !lifetime.isZero) { "Session lifetime must be positive" }
    }

    override fun create(principalId: PrincipalId): IssuedSession = transactor.inTransaction { create(it, principalId) }

    override fun create(
        transaction: Transaction,
        principalId: PrincipalId,
    ): IssuedSession {
        val createdAt = now()
        val token = SessionToken.generate(random)
        val session = PrincipalSession(SessionId(UUID.randomUUID()), principalId, createdAt, createdAt.plus(lifetime), revokedAt = null)
        repository.insert(transaction, session, SessionTokenDigest.of(token))
        logger.info { "event=session_created session=${session.id.value} ${principalId.logFields()} expires_at=${session.expiresAt}" }
        return IssuedSession(session, token)
    }

    override fun resolve(token: SessionToken): PrincipalId? {
        val session = transactor.inTransaction { repository.findByDigest(it, SessionTokenDigest.of(token)) } ?: return null
        return session.principalId.takeIf { session.isActive(now()) }
    }

    override fun revoke(token: SessionToken) = transactor.inTransaction { revoke(it, token) }

    override fun revoke(
        transaction: Transaction,
        token: SessionToken,
    ) {
        if (repository.revoke(transaction, SessionTokenDigest.of(token), now())) logger.info { "event=session_revoked" }
    }

    override fun revokeAll(principalId: PrincipalId) = transactor.inTransaction { revokeAll(it, principalId) }

    override fun revokeAll(
        transaction: Transaction,
        principalId: PrincipalId,
    ) {
        val revoked = repository.revokeAll(transaction, principalId, now())
        logger.info { "event=sessions_revoked ${principalId.logFields()} count=$revoked" }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun PrincipalId.logFields() = "principal_kind=${PrincipalIdColumns.kind(this)} principal=${PrincipalIdColumns.value(this)}"
}
