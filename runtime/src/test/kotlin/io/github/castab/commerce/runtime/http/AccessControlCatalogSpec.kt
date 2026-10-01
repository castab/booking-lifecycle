package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.runtime.authorization.PermissionCatalog
import io.github.castab.commerce.runtime.authorization.commercePermissionDefinitions
import io.github.castab.commerce.runtime.authorization.currentPrincipalHttpCapability
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionGroup
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalResolver
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.http4k.contract.contract
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import java.util.UUID

private val assignInquiries = PermissionKey("catering.inquiries.assign")
private val catalog =
    PermissionCatalog.of(
        commercePermissionDefinitions,
        listOf(PermissionDefinition(assignInquiries, "Assign inquiries", "Assign inquiries to staff.", PermissionGroup("catering"))),
    )
private val staff = User(UserId(UUID.randomUUID()), "staff", null, null, "Staff", PrincipalStatus.ACTIVE, emptySet())

/** Authenticates every request as [staff], standing in for a session or service token. */
private val asStaff = Filter { next -> { request -> next(request.withAuthenticatedPrincipal(staff.id)) } }

private val ok: HttpHandler = { Response(Status.OK).body("handled") }

private fun access(resolver: PermissionResolver) =
    AccessControl(asStaff, catalog, resolver, PrincipalResolver { id: PrincipalId -> if (id == staff.id) staff else null })

/**
 * The binding between `AccessControl` and the running `PermissionCatalog`, without a
 * database: declarations are validated at composition, effective permissions are bounded by
 * the catalog at every resolution, and resolution stays live.
 */
class AccessControlCatalogSpec :
    FunSpec({
        test("registered runtime and application permissions can be declared") {
            val access = access { emptySet() }

            access.requirePermission(CommercePermissions.RoleRead)
            access.requirePermission(CommercePermissions.PrincipalManage)
            access.requirePermission(assignInquiries)
        }

        test("an unregistered permission fails when the route is composed, not when it is requested") {
            var resolutions = 0
            val access =
                access {
                    resolutions++
                    emptySet()
                }

            listOf("commerce.principal.reed", "catering.inquries.assign", "example.missing").forEach { typo ->
                shouldThrow<IllegalArgumentException> { access.requirePermission(PermissionKey(typo)) }.message shouldBe
                    "Permission is not registered in the running PermissionCatalog: $typo"
            }
            resolutions shouldBe 0
        }

        test("a malformed key still fails at the value boundary, before any catalog lookup") {
            shouldThrow<IllegalArgumentException> { PermissionKey("Commerce.Principal.Read") }
            shouldThrow<IllegalArgumentException> { PermissionKey("commerce.principal.read ") }
        }

        test("a resolver returning only catalog keys authorizes as before") {
            val route =
                CommerceErrorHandling.then(
                    access { setOf(CommercePermissions.RoleRead) }.requirePermission(CommercePermissions.RoleRead).then(ok),
                )
            val denied =
                CommerceErrorHandling.then(
                    access { setOf(CommercePermissions.RoleRead) }.requirePermission(CommercePermissions.RoleManage).then(ok),
                )

            route(Request(Method.GET, "/")).bodyString() shouldBe "handled"
            denied(Request(Method.GET, "/")).status shouldBe Status.FORBIDDEN
        }

        test("a resolver returning a key outside the catalog fails closed and never authorizes") {
            val mystery = PermissionKey("mystery.permission")
            var handled = 0
            val handler: HttpHandler = {
                handled++
                Response(Status.OK)
            }
            val bad = access { setOf(CommercePermissions.RoleRead, mystery) }

            // Even the permission the principal does legitimately hold is not granted.
            listOf(CommercePermissions.RoleRead, CommercePermissions.RoleManage).forEach { permission ->
                val response = CommerceErrorHandling.then(bad.requirePermission(permission).then(handler))(Request(Method.GET, "/"))
                response.status shouldBe Status.INTERNAL_SERVER_ERROR
                response.bodyString() shouldContain "internal_failure"
                response.bodyString() shouldNotContain mystery.value
            }
            handled shouldBe 0
            shouldThrow<IllegalStateException> { bad.permissionResolver.permissionsFor(staff.id) }.message shouldBe
                "Effective permissions include keys missing from PermissionCatalog: mystery.permission"
        }

        test("the current principal route never reports a key outside the catalog") {
            val mystery = PermissionKey("mystery.permission")

            fun me(resolver: PermissionResolver) =
                CommerceErrorHandling.then(contract { routes += currentPrincipalHttpCapability(access(resolver), "/me").contractRoutes })

            val good = me { setOf(assignInquiries, CommercePermissions.RoleRead) }(Request(Method.GET, "/me"))
            good.status shouldBe Status.OK
            good.bodyString() shouldContain """"permissions":["catering.inquiries.assign","commerce.role.read"]"""
            good.bodyString() shouldContain catalog.revision

            val bad = me { setOf(CommercePermissions.RoleRead, mystery) }(Request(Method.GET, "/me"))
            bad.status shouldBe Status.INTERNAL_SERVER_ERROR
            bad.bodyString() shouldNotContain mystery.value
        }

        test("effective permissions are resolved live on every request, never captured") {
            val grants = mutableSetOf<PermissionKey>()
            var resolutions = 0
            val route =
                CommerceErrorHandling.then(
                    access {
                        resolutions++
                        grants.toSet()
                    }.requirePermission(assignInquiries).then(ok),
                )

            route(Request(Method.GET, "/")).status shouldBe Status.FORBIDDEN
            grants += assignInquiries
            route(Request(Method.GET, "/")).status shouldBe Status.OK
            grants.clear()
            route(Request(Method.GET, "/")).status shouldBe Status.FORBIDDEN
            resolutions shouldBe 3
        }
    })
