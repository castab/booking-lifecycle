package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.PrincipalSession
import io.github.castab.commerce.runtime.session.SessionId
import io.github.castab.commerce.runtime.session.SessionTokenDigest
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Runtime-owned session storage in `commerce.principal_sessions`, in caller-owned
 * transactions.
 *
 * Internal: applications use [io.github.castab.commerce.runtime.session.SessionManager],
 * never this repository, so no public API accepts stored token material. Timestamps are
 * always supplied by the caller from the runtime's clock; the SQL never reads the database
 * clock, so expiry is judged against one time source.
 */
internal interface PrincipalSessionRepository {
    fun insert(
        transaction: Transaction,
        session: PrincipalSession,
        digest: SessionTokenDigest,
    )

    /** The session whose token has [digest], whatever its state; `null` when there is none. */
    fun findByDigest(
        transaction: Transaction,
        digest: SessionTokenDigest,
    ): PrincipalSession?

    /** Revokes the session with [digest] at [at] if it is still active then. True when a session was revoked. */
    fun revoke(
        transaction: Transaction,
        digest: SessionTokenDigest,
        at: Instant,
    ): Boolean

    /** Revokes, at [at], every session of [principalId] still active then. Returns how many were revoked. */
    fun revokeAll(
        transaction: Transaction,
        principalId: PrincipalId,
        at: Instant,
    ): Int
}

/**
 * The explicit set of [PrincipalId] representations commerce-runtime persists: a kind
 * discriminator and the identifier's UUID. It never uses class names, `toString`, or any
 * generic serialization.
 *
 * This is a deliberate, fail-closed contract. A new `PrincipalId` subtype in
 * commerce-domain is not automatically persistable: `PrincipalId` is sealed, so these
 * `when` expressions stop compiling until the runtime adds the subtype's encoding and
 * decoding here, and a new runtime migration widens the `principal_kind` check constraint
 * of `commerce.principal_sessions`. Only then can sessions of that kind be stored.
 */
internal object PrincipalIdColumns {
    const val USER = "USER"
    const val SERVICE = "SERVICE"

    fun kind(principalId: PrincipalId): String =
        when (principalId) {
            is UserId -> USER
            is ServiceId -> SERVICE
        }

    fun value(principalId: PrincipalId): UUID =
        when (principalId) {
            is UserId -> principalId.value
            is ServiceId -> principalId.value
        }

    fun restore(
        kind: String,
        value: UUID,
    ): PrincipalId =
        when (kind) {
            USER -> UserId(value)
            SERVICE -> ServiceId(value)
            else -> error("Unsupported stored principal kind: $kind")
        }
}

internal class PostgresPrincipalSessionRepository : PrincipalSessionRepository {
    override fun insert(
        transaction: Transaction,
        session: PrincipalSession,
        digest: SessionTokenDigest,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.principal_sessions
                       (session_id, principal_kind, principal_id, token_digest, created_at, expires_at, revoked_at)
                       VALUES (:sessionId, :principalKind, :principalId, :tokenDigest, :createdAt, :expiresAt, :revokedAt)""",
                ).bind("sessionId", session.id.value)
                .bind("principalKind", PrincipalIdColumns.kind(session.principalId))
                .bind("principalId", PrincipalIdColumns.value(session.principalId))
                .bind("tokenDigest", digest.bytes())
                .bind("createdAt", session.createdAt.timestamp())
                .bind("expiresAt", session.expiresAt.timestamp())
                .bind("revokedAt", session.revokedAt?.timestamp())
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            // Neither the digest nor the statement's parameters appear in the message.
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Session ${session.id.value} is not unique", e)
            throw e
        }
    }

    override fun findByDigest(
        transaction: Transaction,
        digest: SessionTokenDigest,
    ): PrincipalSession? =
        transaction.handle
            .createQuery(
                """SELECT session_id, principal_kind, principal_id, token_digest, created_at, expires_at, revoked_at
                   FROM commerce.principal_sessions WHERE token_digest = :tokenDigest""",
            ).bind("tokenDigest", digest.bytes())
            .map { rows, _ -> SessionTokenDigest.restore(rows.getBytes("token_digest")) to session(rows) }
            .findOne()
            .orElse(null)
            // The index lookup found it; confirm the match in constant time as well.
            ?.takeIf { (stored, _) -> stored == digest }
            ?.second

    override fun revoke(
        transaction: Transaction,
        digest: SessionTokenDigest,
        at: Instant,
    ): Boolean =
        transaction.handle
            .createUpdate(
                """UPDATE commerce.principal_sessions SET revoked_at = :at
                   WHERE token_digest = :tokenDigest AND revoked_at IS NULL AND created_at <= :at AND expires_at > :at""",
            ).bind("tokenDigest", digest.bytes())
            .bind("at", at.timestamp())
            .execute() > 0

    override fun revokeAll(
        transaction: Transaction,
        principalId: PrincipalId,
        at: Instant,
    ): Int =
        transaction.handle
            .createUpdate(
                """UPDATE commerce.principal_sessions SET revoked_at = :at
                   WHERE principal_kind = :principalKind AND principal_id = :principalId
                     AND revoked_at IS NULL AND created_at <= :at AND expires_at > :at""",
            ).bind("principalKind", PrincipalIdColumns.kind(principalId))
            .bind("principalId", PrincipalIdColumns.value(principalId))
            .bind("at", at.timestamp())
            .execute()

    private fun session(rows: ResultSet): PrincipalSession =
        PrincipalSession(
            SessionId(rows.getObject("session_id", UUID::class.java)),
            PrincipalIdColumns.restore(rows.getString("principal_kind"), rows.getObject("principal_id", UUID::class.java)),
            rows.instant("created_at")!!,
            rows.instant("expires_at")!!,
            rows.instant("revoked_at"),
        )

    private fun Instant.timestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
