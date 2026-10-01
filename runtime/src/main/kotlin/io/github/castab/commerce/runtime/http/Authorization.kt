package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.can
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Lens
import org.http4k.lens.LensFailure
import org.http4k.lens.RequestKey

private val principalKey = RequestKey.required<PrincipalId>("authenticatedPrincipal")

/**
 * The principal an authentication filter established for this request: [authentication]
 * over one or more mechanisms, `sessionAuthentication`, or `serviceAccessTokenAuthentication`.
 * It is the same `PrincipalId` whichever mechanism established it, a `UserId` or a
 * `ServiceId`, so authorization never depends on the mechanism.
 *
 * It lives in the http4k request context, never in global or thread-local state, and it is
 * read-only: only the runtime's authentication filters set it. Reading it on a request that
 * was not authenticated fails closed; [CommerceErrorHandling] answers `401 unauthenticated`.
 */
val authenticatedPrincipal: Lens<Request, PrincipalId> = Lens(principalKey.meta) { request: Request -> principalKey(request) }

@JvmSynthetic
internal fun Request.withAuthenticatedPrincipal(principalId: PrincipalId): Request = with(principalKey of principalId)

private fun Request.authenticatedPrincipalOrNull(): PrincipalId? =
    try {
        principalKey(this)
    } catch (_: LensFailure) {
        null
    }

/** Whether a runtime authentication filter has already established [authenticatedPrincipal] for this request. */
@JvmSynthetic
internal fun Request.hasAuthenticatedPrincipal(): Boolean = authenticatedPrincipalOrNull() != null

/** True when [failure] is a read of [authenticatedPrincipal] on a request that was not authenticated. */
internal fun isMissingAuthenticatedPrincipal(failure: LensFailure): Boolean =
    failure.failures.isNotEmpty() && failure.failures.all { it.meta == principalKey.meta }

internal fun unauthenticatedResponse() = errorResponse(ErrorCategory.UNAUTHENTICATED, "Authentication is required")

/** Answers `401 unauthenticated` unless an authentication filter established [authenticatedPrincipal]. */
val requireAuthenticatedPrincipal: Filter =
    Filter { next ->
        { request -> if (request.hasAuthenticatedPrincipal()) next(request) else unauthenticatedResponse() }
    }

/**
 * Allows the request only when its [authenticatedPrincipal] currently holds [permission]:
 *
 * - no authenticated principal: `401 unauthenticated`;
 * - an authenticated principal without [permission]: `403 forbidden`;
 * - otherwise the next handler runs.
 *
 * The decision uses `PrincipalId.can` with [permissionResolver] on every request. Nothing
 * is cached, and nothing was captured when the session began, so role and principal
 * changes apply to sessions that are already active. It works for every kind of
 * `PrincipalId`, human or service.
 */
fun requirePermission(
    permission: PermissionKey,
    permissionResolver: PermissionResolver,
): Filter =
    Filter { next ->
        { request ->
            val principal = request.authenticatedPrincipalOrNull()
            when {
                principal == null -> unauthenticatedResponse()
                !principal.can(permission, permissionResolver) ->
                    errorResponse(ErrorCategory.FORBIDDEN, "The authenticated principal is not permitted to perform this request")
                else -> next(request)
            }
        }
    }

/**
 * Declarative route protection: every route states whether it is [public], merely
 * [authenticated], or requires a permission ([requirePermission]).
 *
 * **Authentication is conditional.** [authentication] is a fallback: it runs only when no
 * runtime authentication filter has already established [authenticatedPrincipal] for the
 * request. Once one has, nested `AccessControl` instances reuse that principal and never
 * authenticate the request again, whatever transport their own [authentication] uses.
 * Authentication transport precedence therefore follows request composition order: an
 * outer `sessionAuthentication(sessions, sessionCookie)` that establishes a `UserId` wins
 * over a nested `AccessControl` configured for bearer tokens, whose bearer credentials are
 * not inspected. A request carrying credentials for two different principals is
 * authenticated as whichever the responsible filter established; nothing reconciles them.
 *
 * **Authorization is live.** [permissionResolver], the application's, is consulted through
 * `PrincipalId.can` on every request, including when the principal was reused. Nothing is
 * cached.
 *
 * Protection fails closed: the protected declarations re-check the principal after
 * authentication, so a lenient [authentication] still cannot expose them.
 *
 * **A service is not trusted because it is a service.** A service access token establishes a
 * `ServiceId` exactly as a session establishes a `UserId`; a service without the permission
 * is forbidden like anyone else.
 *
 * @param authentication Establishes [authenticatedPrincipal] when the request is not yet
 *   authenticated, for example `sessionAuthentication(context.sessions, SessionCookie("app_session"))`,
 *   or [authentication] over several mechanisms to accept users' sessions and services'
 *   access tokens on the same routes.
 * @param permissionResolver The application's source of current permissions.
 *
 * ```kotlin
 * val access =
 *     AccessControl(
 *         authentication(
 *             SessionAuthenticator(context.sessions, SessionCookie("__Host-session")),
 *             ServiceAccessTokenAuthenticator(context.serviceAccessTokens),
 *         ),
 *         context.authorization.permissionResolver,
 *     )
 * routes(
 *     "/login" bind POST to access.public().then(login),
 *     "/logout" bind POST to access.authenticated().then(logout),
 *     "/bookings" bind GET to access.requirePermission(CommercePermissions.BookingRead).then(listBookings),
 * )
 * ```
 */
class AccessControl(
    private val authentication: Filter,
    private val permissionResolver: PermissionResolver,
) {
    /**
     * [authentication] as a fallback: skipped when the request already carries a
     * runtime-established [authenticatedPrincipal], so the session is resolved once per
     * request and an established principal is never replaced. It gates authentication only;
     * the filters it precedes always run.
     */
    private val authenticateUnlessAuthenticated =
        Filter { next ->
            val authenticateThenNext: HttpHandler = authentication.then(next)
            val handler: HttpHandler = { request ->
                if (request.hasAuthenticatedPrincipal()) next(request) else authenticateThenNext(request)
            }
            handler
        }

    /** Explicitly public: no authentication and no permission. */
    fun public(): Filter = Filter.NoOp

    /** Any authenticated principal, without a particular permission. */
    fun authenticated(): Filter = authenticateUnlessAuthenticated.then(requireAuthenticatedPrincipal)

    /** An authenticated principal that currently holds [permission]. */
    fun requirePermission(permission: PermissionKey): Filter =
        authenticateUnlessAuthenticated.then(requirePermission(permission, permissionResolver))
}
