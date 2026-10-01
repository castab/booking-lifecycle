package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.CommerceRuntimeContext
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
 * `GET {path}`: the authenticated principal and its current effective permissions, so a
 * frontend can decide what to show without resolving roles itself.
 *
 * Any authenticated principal may read its own entry; there is no permission requirement,
 * and an unauthenticated request is `401`. Permissions come from
 * [AccessControl.permissionResolver], the same resolver that enforces every
 * `requirePermission` declaration, and are resolved on every request, so role and grant
 * changes appear without a new session. A principal with no grants receives an empty list.
 *
 * Effective permissions answer "what may this principal do now?"; the [PermissionCatalog]
 * answers "what permissions exist?". Hiding controls a principal lacks is user experience
 * only: the server keeps enforcing each operation's permission. Role keys are deliberately
 * absent, because clients must not derive authority from role names.
 *
 * The host chooses [path] (for example `/me`) and its OpenAPI [tags].
 */
fun currentPrincipalHttpCapability(
    context: CommerceRuntimeContext,
    accessControl: AccessControl,
    path: String,
    tags: Set<Tag> = emptySet(),
): CurrentPrincipalHttpCapability {
    requireRoutePath(path) { "Invalid current principal path" }
    requireTagNames(tags)
    val directory = context.authorization
    val currentBody = jsonBody(CurrentPrincipalDto.serializer())
    val sample =
        CurrentPrincipalDto(
            PrincipalSummaryDto("USER", UUID(0, 1).toString(), "Staff Member"),
            listOf(CommercePermissions.PrincipalRead.value, CommercePermissions.RoleRead.value),
            directory.permissionCatalog.revision,
        )
    val route =
        path meta {
            operationId = "authorizationCurrentPrincipal"
            summary = "Get the authenticated principal and its effective permissions"
            this.tags += tags
            returning(Status.OK, currentBody to sample)
            errors(Status.UNAUTHORIZED)
        } bindContract Method.GET to
            accessControl.authenticated().then { request: Request ->
                val id = authenticatedPrincipal(request)
                // A principal removed after authentication is no longer an identity to describe.
                val principal = directory.principalResolver.resolve(id)?.takeIf { it.id == id }
                if (principal == null) {
                    unauthenticatedResponse()
                } else {
                    val permissions =
                        accessControl.permissionResolver
                            .permissionsFor(id)
                            .map { it.value }
                            .sorted()
                    Response(Status.OK).with(
                        currentBody of CurrentPrincipalDto(principal.summary(), permissions, directory.permissionCatalog.revision),
                    )
                }
            }
    return CurrentPrincipalHttpCapability(listOf(route))
}
