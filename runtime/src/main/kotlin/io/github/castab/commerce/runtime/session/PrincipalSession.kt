package io.github.castab.commerce.runtime.session

import io.github.castab.commerce.staff.PrincipalId
import java.time.Instant
import java.util.UUID

/**
 * Stable identity of one [PrincipalSession].
 *
 * It is not a secret and is never accepted as a credential: it cannot be parsed as a
 * [SessionToken], and nothing derives a token from it.
 */
@JvmInline
value class SessionId(
    val value: UUID,
)

/**
 * One authenticated session of exactly one principal, as persisted by commerce-runtime.
 *
 * A session establishes identity, not authority: it records who authenticated, not what
 * they may do. Permissions are never captured in a session; every authorization decision
 * asks the current `PermissionResolver`.
 *
 * The session holds no credential material. Its [SessionToken] is returned once, in the
 * [IssuedSession] that created it, and only a digest of that token is stored.
 *
 * A session is active from [createdAt] until [expiresAt] (exclusive), unless it was
 * revoked earlier; see [isActive]. Its lifetime is fixed: resolving a session never
 * extends it.
 */
data class PrincipalSession(
    val id: SessionId,
    val principalId: PrincipalId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
) {
    init {
        require(expiresAt.isAfter(createdAt)) { "Session ${id.value} must expire after it was created" }
        require(revokedAt == null || !revokedAt.isBefore(createdAt)) {
            "Session ${id.value} cannot be revoked before it was created"
        }
    }

    /** Whether this session authenticates its principal at [at]: created, not yet expired, and not yet revoked. */
    fun isActive(at: Instant): Boolean =
        !at.isBefore(createdAt) &&
            at.isBefore(expiresAt) &&
            (revokedAt == null || at.isBefore(revokedAt))
}

/**
 * The result of creating a session: the persisted [session] and the [token] that
 * authenticates it.
 *
 * This is the only place the runtime ever hands out a raw token. The application delivers
 * [token] to its client, for example in a cookie ([SessionCookie]) or a response body, and
 * must not persist or log it. The token cannot be recovered later. [toString] redacts it.
 */
data class IssuedSession(
    val session: PrincipalSession,
    val token: SessionToken,
)
