package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.staff.PrincipalId
import org.http4k.core.Filter
import org.http4k.core.Request

/**
 * One authentication mechanism: the transport and verification that prove which principal
 * sent a request, for example a session cookie (`SessionAuthenticator`) or a service access
 * token (`ServiceAccessTokenAuthenticator`).
 *
 * [authenticate] answers the principal the request's credential proves, or `null` when the
 * request carries no credential this mechanism accepts: missing, malformed, unknown,
 * expired, revoked, or belonging to a principal that is missing or disabled. It establishes
 * identity only. It never decides authorization, which `requirePermission` and
 * [AccessControl] evaluate against the current `PermissionResolver` on every request.
 */
fun interface RequestAuthenticator {
    fun authenticate(request: Request): PrincipalId?
}

/**
 * Authenticates each request through the first of [authenticators] that proves a
 * principal, or rejects it with `401 unauthenticated`.
 *
 * - If a runtime authentication filter has already established the request's
 *   [authenticatedPrincipal], this filter reuses it and consults no authenticator.
 * - Otherwise [authenticators] are tried in the order given. The first non-`null` principal
 *   becomes the request's [authenticatedPrincipal] and the next handler runs; later
 *   mechanisms are not consulted. Nothing compares or reconciles the credentials of two
 *   mechanisms: a request is authenticated as one principal.
 * - When no mechanism proves a principal the answer is `401 unauthenticated` with the same
 *   message whatever went wrong, never revealing which mechanism failed or why.
 *
 * Every mechanism yields the same kind of result, a `PrincipalId`, so authorization never
 * depends on how a principal authenticated: a user's cookie session and a service's
 * access token reach the same [AccessControl]. Transport is not authorization; an endpoint
 * that must require a particular mechanism needs that modeled explicitly.
 *
 * ```kotlin
 * val authenticate =
 *     authentication(
 *         SessionAuthenticator(context.sessions, SessionCookie("__Host-session")),
 *         ServiceAccessTokenAuthenticator(context.serviceAccessTokens),
 *     )
 * val access = AccessControl(authenticate, context.authorization.permissionResolver)
 * ```
 */
fun authentication(vararg authenticators: RequestAuthenticator): Filter {
    require(authenticators.isNotEmpty()) { "At least one authentication mechanism is required" }
    val mechanisms = authenticators.toList()
    return Filter { next ->
        { request ->
            if (request.hasAuthenticatedPrincipal()) {
                next(request)
            } else {
                when (val principal = mechanisms.firstNotNullOfOrNull { it.authenticate(request) }) {
                    null -> unauthenticatedResponse()
                    else -> next(request.withAuthenticatedPrincipal(principal))
                }
            }
        }
    }
}

/**
 * The credential of an `Authorization: Bearer <credential>` header, or `null` when there is
 * none. The scheme is case-insensitive. The credential is not interpreted here: each
 * mechanism parses its own format, so session tokens and service access tokens can share
 * the header without being mistaken for each other.
 */
@JvmSynthetic
internal fun Request.bearerCredential(): String? {
    val authorization = header("Authorization")?.trim() ?: return null
    val scheme = authorization.substringBefore(' ')
    if (!scheme.equals("Bearer", ignoreCase = true) || !authorization.contains(' ')) return null
    return authorization.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
}
