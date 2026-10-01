package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.http.unauthenticatedResponse
import io.github.castab.commerce.staff.CommercePermissions
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import java.util.UUID

/** Contract routes that a host mounts in its own http4k/OpenAPI contract. */
class CurrentPrincipalHttpCapability internal constructor(
    val contractRoutes: List<ContractRoute>,
)

/**
 * `GET {path}`: the principal authenticated for this request and its current effective
 * permissions, so a client can decide what to show without resolving roles itself.
 *
 * **It describes the request's principal, `USER` or `SERVICE`.** It reports whoever
 * authenticated the backend request, not "the human in front of a frontend". When a
 * backend-for-frontend authenticates to this application with its own service credential
 * (browser user → BFF → commerce backend as a `SERVICE` principal), a `GET {path}` with that
 * credential correctly returns the BFF's service identity and the service's permissions. It
 * does not, and cannot, recover the browser user's identity: there is no delegation,
 * impersonation, or forwarded-user semantics. A service is described like any other
 * principal and gains nothing for being one.
 *
 * Any authenticated principal may read its own entry; there is no permission requirement,
 * and an unauthenticated request is `401`. Identity, effective permissions, and
 * `permissionCatalogRevision` all come from [accessControl], so they describe the same
 * authorization it enforces. Permissions are resolved on every request, so role and grant
 * changes appear without a new session; a principal with no grants receives an empty list.
 * A resolution containing a key outside the catalog fails closed with an internal error and
 * is never reported.
 *
 * Effective permissions answer "what may this principal do now?"; the [PermissionCatalog]
 * answers "what permissions exist?". Hiding controls a principal lacks is user experience
 * only: the server keeps enforcing each operation's permission. Role keys are deliberately
 * absent, because clients must not derive authority from role names.
 *
 * The host chooses [path] (for example `/me`) and its OpenAPI [tags].
 */
fun currentPrincipalHttpCapability(
    accessControl: AccessControl,
    path: String,
    tags: Set<Tag> = emptySet(),
): CurrentPrincipalHttpCapability {
    requireRoutePath(path) { "Invalid current principal path" }
    requireTagNames(tags)
    val catalog = accessControl.permissionCatalog
    val currentBody = jsonBody(CurrentPrincipalDto.serializer())
    val sample =
        CurrentPrincipalDto(
            PrincipalSummaryDto("USER", UUID(0, 1).toString(), "Staff Member"),
            listOf(CommercePermissions.PrincipalRead.value, CommercePermissions.RoleRead.value),
            catalog.revision,
        )
    val route =
        path meta {
            operationId = "authorizationCurrentPrincipal"
            summary = "Get the authenticated request principal (USER or SERVICE) and its effective permissions"
            description =
                "Describes the principal that authenticated this request. A service calling with its own credential " +
                "receives its own identity, never that of a user it acts for."
            this.tags += tags
            returning(Status.OK, currentBody to sample)
            errors(Status.UNAUTHORIZED)
        } bindContract Method.GET to
            accessControl.authenticated().then { request: Request ->
                val id = authenticatedPrincipal(request)
                // A principal removed after authentication is no longer an identity to describe.
                val principal = accessControl.principalResolver.resolve(id)?.takeIf { it.id == id }
                if (principal == null) {
                    unauthenticatedResponse()
                } else {
                    val permissions =
                        accessControl.permissionResolver
                            .permissionsFor(id)
                            .map { it.value }
                            .sorted()
                    Response(Status.OK).with(currentBody of CurrentPrincipalDto(principal.summary(), permissions, catalog.revision))
                }
            }
    return CurrentPrincipalHttpCapability(listOf(route))
}
