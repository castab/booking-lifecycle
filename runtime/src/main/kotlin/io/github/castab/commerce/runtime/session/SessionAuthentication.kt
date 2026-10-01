package io.github.castab.commerce.runtime.session

import io.github.castab.commerce.runtime.http.RequestAuthenticator
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.http.bearerCredential
import io.github.castab.commerce.staff.PrincipalId
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import java.time.Instant

/**
 * Finds the session token a request presents. Transport only: it does not decide whether
 * the token is valid. A missing or malformed token is `null`.
 */
fun interface SessionTokenExtractor {
    fun extract(request: Request): SessionToken?
}

/**
 * Reads the token from `Authorization: Bearer <token>`. The scheme is case-insensitive. A
 * bearer credential in another format, such as a service access token, is not a session
 * token and yields `null`.
 */
object BearerSessionToken : SessionTokenExtractor {
    override fun extract(request: Request): SessionToken? = request.bearerCredential()?.let(SessionToken::parse)
}

/**
 * A browser session cookie: an optional adapter over sessions, reading the token from the
 * cookie [name] and building the cookies that set and clear it.
 *
 * The cookie is always `Secure` and `HttpOnly`, has no `Domain` (host-only), and uses
 * [sameSite] (`Lax` by default) and [path]. Naming it with the `__Host-` prefix, for
 * example `__Host-session`, additionally stops subdomains from setting it. The application
 * chooses [name] and decides when to set or clear the cookie. Cross-site request forgery
 * protection beyond `SameSite` remains the application's responsibility.
 */
class SessionCookie(
    val name: String,
    val sameSite: SameSite = SameSite.Lax,
    val path: String = "/",
) : SessionTokenExtractor {
    init {
        require(name.matches(cookieName)) { "Session cookie name must be a valid cookie name" }
        require(path.startsWith('/')) { "Session cookie path must begin with /" }
        require(!name.startsWith("__Host-") || path == "/") { "A __Host- session cookie must have the path /" }
    }

    override fun extract(request: Request): SessionToken? = request.cookie(name)?.value?.let(SessionToken::parse)

    /** The cookie that delivers [issued]'s token; it expires with the session. */
    fun issue(issued: IssuedSession): Cookie = cookie(issued.token.value, expires = issued.session.expiresAt, maxAge = null)

    /** The cookie that removes the session cookie from the browser, for example on logout. Revoke the session as well. */
    fun clear(): Cookie = cookie("", expires = Instant.EPOCH, maxAge = 0)

    private fun cookie(
        value: String,
        expires: Instant,
        maxAge: Long?,
    ) = Cookie(name, value, maxAge = maxAge, expires = expires, path = path, secure = true, httpOnly = true, sameSite = sameSite)

    override fun toString(): String = "SessionCookie(name=$name, sameSite=$sameSite, path=$path)"

    private companion object {
        // RFC 6265 cookie-name: an RFC 7230 token.
        val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}

/**
 * The session authentication mechanism: [extractor] finds the request's token and
 * [sessions] resolves it to the principal of an active session, or `null`.
 *
 * Use it directly with [authentication] to accept sessions alongside other mechanisms, for
 * example service access tokens; [sessionAuthentication] is the single-mechanism filter.
 */
class SessionAuthenticator(
    private val sessions: SessionManager,
    private val extractor: SessionTokenExtractor,
) : RequestAuthenticator {
    override fun authenticate(request: Request): PrincipalId? = extractor.extract(request)?.let(sessions::resolve)
}

/**
 * Authenticates each request through a session, or rejects it. It is
 * [authentication] with one [SessionAuthenticator].
 *
 * If a runtime authentication filter has already established the request's
 * [authenticatedPrincipal][io.github.castab.commerce.runtime.http.authenticatedPrincipal],
 * this filter reuses it and does nothing else. Otherwise:
 *
 * 1. [extractor] finds the request's token;
 * 2. [sessions] resolves it to a `PrincipalId`;
 * 3. the principal becomes the request's
 *    [authenticatedPrincipal][io.github.castab.commerce.runtime.http.authenticatedPrincipal]
 *    and the next handler runs.
 *
 * A missing, malformed, unknown, expired, or revoked token is answered with
 * `401 unauthenticated` and the same message, never revealing which. Authorization is
 * separate; see `requirePermission` and `AccessControl`.
 *
 * `sessionAuthentication` is idempotent for a request that already has a
 * runtime-authenticated principal. The first runtime authentication filter to establish
 * the principal wins; nested session-authentication filters reuse it without inspecting
 * their own transport, so they neither replace it nor answer `401` because their
 * transport is absent. Competing credentials are not compared or reconciled.
 *
 * Authentication transport is not authorization. An endpoint that must require a
 * particular authentication mechanism or assurance level needs that modeled explicitly;
 * it must not rely on the order in which authentication filters are nested.
 */
fun sessionAuthentication(
    sessions: SessionManager,
    extractor: SessionTokenExtractor,
): Filter = authentication(SessionAuthenticator(sessions, extractor))
