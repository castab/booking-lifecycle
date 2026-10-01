package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import org.http4k.contract.ContractRoute
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with

/** Contract routes that a host mounts in its own http4k/OpenAPI contract. */
class PermissionCatalogHttpCapability internal constructor(
    val contractRoutes: List<ContractRoute>,
)

/**
 * Read-only exposure of the running application's [catalog] at `GET {basePath}/permissions`,
 * for hosts that want the authorization vocabulary without mounting principal and role
 * administration. [authorizationAdministrationHttpCapability] already includes the same
 * route; mount one or the other at a given base path.
 *
 * The route requires `CommercePermissions.RoleRead` through the application's
 * [accessControl]: `401` without a principal, `403` without the permission. Reading the
 * vocabulary is distinct from changing roles (`RoleManage`). Permission keys are not
 * secrets, and the catalog confers no authority; every protected operation still enforces
 * its own permission. [tags] are the host's OpenAPI grouping for the route.
 */
fun permissionCatalogHttpCapability(
    catalog: PermissionCatalog,
    accessControl: AccessControl,
    basePath: String,
    tags: Set<Tag> = emptySet(),
): PermissionCatalogHttpCapability {
    requireRoutePath(basePath) { "Invalid permission catalog base path" }
    requireTagNames(tags)
    return PermissionCatalogHttpCapability(listOf(permissionCatalogRoute(catalog, accessControl, basePath, tags)))
}

internal fun permissionCatalogRoute(
    catalog: PermissionCatalog,
    accessControl: AccessControl,
    basePath: String,
    tags: Set<Tag>,
): ContractRoute {
    val permissionsBody = jsonBody(PermissionsDto.serializer())
    val sample = PermissionCatalog(commercePermissionDefinitions.filter { it.key == CommercePermissions.RoleRead }).dto()
    // The catalog is immutable, so its response is built once.
    val response = catalog.dto()
    return "$basePath/permissions" meta {
        operationId = "authorizationListPermissions"
        summary = "List the permission catalog"
        description = "Every permission known to the running application, ordered by key, with the catalog revision."
        this.tags += tags
        returning(Status.OK, permissionsBody to sample)
        errors(Status.UNAUTHORIZED, Status.FORBIDDEN)
    } bindContract Method.GET to
        accessControl.requirePermission(CommercePermissions.RoleRead).then { _: Request ->
            Response(Status.OK).with(permissionsBody of response)
        }
}

/** Documents the standard `{"code", "message"}` error body for each of [statuses]. */
internal fun RouteMetaDsl.errors(vararg statuses: Status) {
    val errorBody = jsonBody(ErrorResponse.serializer())
    statuses.forEach { status ->
        val category = ErrorCategory.entries.first { it.status == status }
        returning(status, errorBody to ErrorResponse(category.code, "Request failed"))
    }
}

/** An absolute route path without a trailing slash, empty segments, or query and template characters. */
internal fun requireRoutePath(
    path: String,
    message: () -> String,
) = require(
    path.startsWith('/') &&
        path.length > 1 &&
        !path.endsWith('/') &&
        path.split('/').drop(1).all { it.isNotBlank() } &&
        path.none { it in "?{}#" },
    message,
)

internal fun requireTagNames(tags: Set<Tag>) = require(tags.none { it.name.isBlank() }) { "OpenAPI tag names cannot be blank" }
