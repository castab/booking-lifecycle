package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.can
import org.http4k.core.Filter
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Lens
import org.http4k.lens.LensFailure
import org.http4k.lens.RequestKey

private val principalKey = RequestKey.required<PrincipalId>("authenticatedPrincipal")

/**
 * The principal an authentication filter established for this request, such as
 * `sessionAuthentication`.
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

/** True when [failure] is a read of [authenticatedPrincipal] on a request that was not authenticated. */
internal fun isMissingAuthenticatedPrincipal(failure: LensFailure): Boolean =
    failure.failures.isNotEmpty() && failure.failures.all { it.meta == principalKey.meta }

internal fun unauthenticatedResponse() = errorResponse(ErrorCategory.UNAUTHENTICATED, "Authentication is required")

/** Answers `401 unauthenticated` unless an authentication filter established [authenticatedPrincipal]. */
val requireAuthenticatedPrincipal: Filter =
    Filter { next ->
        { request -> if (request.authenticatedPrincipalOrNull() == null) unauthenticatedResponse() else next(request) }
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
 * [authentication] establishes [authenticatedPrincipal], for example
 * `sessionAuthentication(context.sessions, SessionCookie("app_session"))`; the
 * application's [permissionResolver] supplies current authority. Protection fails closed:
 * the protected declarations re-check the principal, so a lenient [authentication] still
 * cannot expose them.
 *
 * ```kotlin
 * val access = AccessControl(sessionAuthentication(context.sessions, BearerSessionToken), permissionResolver)
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
    /** Explicitly public: no authentication and no permission. */
    fun public(): Filter = Filter.NoOp

    /** Any authenticated principal, without a particular permission. */
    fun authenticated(): Filter = authentication.then(requireAuthenticatedPrincipal)

    /** An authenticated principal that currently holds [permission]. */
    fun requirePermission(permission: PermissionKey): Filter = authentication.then(requirePermission(permission, permissionResolver))
}
