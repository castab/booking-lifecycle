package io.github.castab.commerce.staff

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class AuthorizationSpec :
    FunSpec({
        val userId = UserId(UUID.randomUUID())
        val serviceId = ServiceId(UUID.randomUUID())
        val observePayments = PermissionKey("example.payment.observe")
        val paymentAdapter = RoleKey("example.payment-adapter")
        val accounting = RoleKey("example.accounting")

        fun user(
            status: UserStatus = UserStatus.ACTIVE,
            roles: Set<RoleAssignment> = setOf(RoleAssignment(paymentAdapter)),
        ) = User(userId, "alex", "Alex", null, "Alex", status, roles)

        fun role(
            key: RoleKey = paymentAdapter,
            permissions: Set<PermissionKey> = setOf(observePayments),
        ) = RoleDefinition(key, "Role", null, permissions)

        fun service(
            status: PrincipalStatus = PrincipalStatus.ACTIVE,
            roles: Set<RoleAssignment> = setOf(RoleAssignment(paymentAdapter)),
        ) = ServiceIdentity(serviceId, "stripe-adapter", status, roles)

        test("user identity and fields are independent of authentication") {
            val id = UUID.randomUUID()
            val staff = User(UserId(id), "alex", "Alex", "Ng", "Alex Ng", UserStatus.ACTIVE, emptySet())

            staff.id.value shouldBe id
            staff.username shouldBe "alex"
            staff.firstName shouldBe "Alex"
            staff.lastName shouldBe "Ng"
            staff.displayName shouldBe "Alex Ng"
            staff.status shouldBe UserStatus.ACTIVE
            staff.roles shouldBe emptySet()
            val humanResolver = UserResolver { if (it == staff.id) staff else null }
            humanResolver.resolve(staff.id) shouldBe staff
        }

        test("human and service IDs are distinct principal identities") {
            val uuid = UUID.randomUUID()
            val human: PrincipalId = UserId(uuid)
            val machine: PrincipalId = ServiceId(uuid)

            human shouldBe UserId(uuid)
            machine shouldBe ServiceId(uuid)
            (human == machine) shouldBe false
            (user() as Principal).id shouldBe userId
            (service() as Principal).id shouldBe serviceId
        }

        test("text values reject blanks without imposing application-specific syntax") {
            shouldThrow<IllegalArgumentException> { RoleKey(" ") }
            shouldThrow<IllegalArgumentException> { PermissionKey("\t") }
            shouldThrow<IllegalArgumentException> { User(userId, "", null, null, "Alex", UserStatus.ACTIVE, emptySet()) }
            shouldThrow<IllegalArgumentException> { User(userId, "alex", null, null, " ", UserStatus.ACTIVE, emptySet()) }
            shouldThrow<IllegalArgumentException> { RoleDefinition(paymentAdapter, " ", null, emptySet()) }
            shouldThrow<IllegalArgumentException> { ServiceIdentity(serviceId, " ", PrincipalStatus.ACTIVE, emptySet()) }
            RoleKey("example.booking-coordinator").value shouldBe "example.booking-coordinator"
            observePayments.value shouldBe "example.payment.observe"
        }

        test("users and role definitions copy their sets") {
            val assignments = mutableSetOf(RoleAssignment(paymentAdapter))
            val staff = user(roles = assignments)
            assignments.clear()
            staff.roles shouldBe setOf(RoleAssignment(paymentAdapter))
            shouldThrow<UnsupportedOperationException> { (staff.roles as MutableSet).clear() }

            val grants = mutableSetOf(observePayments)
            val definition = role(permissions = grants)
            grants.clear()
            definition.permissions shouldBe setOf(observePayments)
            shouldThrow<UnsupportedOperationException> { (definition.permissions as MutableSet).clear() }
        }

        test("service identities copy assigned roles") {
            val assignments = mutableSetOf(RoleAssignment(paymentAdapter))
            val machine = service(roles = assignments)
            assignments.clear()

            machine.name shouldBe "stripe-adapter"
            machine.roles shouldBe setOf(RoleAssignment(paymentAdapter))
            shouldThrow<UnsupportedOperationException> { (machine.roles as MutableSet).clear() }
        }

        test("one assigned role grants its permission through the extension") {
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { user() }, RoleResolver { role(it) })

            resolver.permissionsFor(userId) shouldBe setOf(observePayments)
            userId.can(observePayments, resolver) shouldBe true
            userId.can(CommercePermissions.PaymentRecord, resolver) shouldBe false
        }

        test("multiple roles union their grants and collapse duplicates") {
            val staff = user(roles = setOf(RoleAssignment(paymentAdapter), RoleAssignment(accounting)))
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver { staff },
                    RoleResolver {
                        when (it) {
                            paymentAdapter -> role(permissions = setOf(observePayments, CommercePermissions.PaymentRecord))
                            accounting -> role(accounting, setOf(CommercePermissions.PaymentRecord, CommercePermissions.RefundRecord))
                            else -> null
                        }
                    },
                )

            resolver.permissionsFor(userId) shouldBe
                setOf(observePayments, CommercePermissions.PaymentRecord, CommercePermissions.RefundRecord)
            userId.can(CommercePermissions.RefundRecord, resolver) shouldBe true
            userId.can(CommercePermissions.BookingModify, resolver) shouldBe false
        }

        test("unknown users fail closed without resolving roles") {
            val roles = mockk<RoleResolver>()
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { null }, roles)

            resolver.permissionsFor(userId) shouldBe emptySet()
            userId.can(observePayments, resolver) shouldBe false
            verify { roles wasNot Called }
        }

        test("a resolver returning a different user fails closed") {
            val roles = mockk<RoleResolver>()
            val other =
                User(UserId(UUID.randomUUID()), "other", null, null, "Other", UserStatus.ACTIVE, setOf(RoleAssignment(paymentAdapter)))
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { other }, roles)

            resolver.permissionsFor(userId) shouldBe emptySet()
            verify { roles wasNot Called }
        }

        test("disabled users receive no grants and do not trigger role resolution") {
            val roles = mockk<RoleResolver>()
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { user(UserStatus.DISABLED) }, roles)

            resolver.permissionsFor(userId) shouldBe emptySet()
            userId.can(observePayments, resolver) shouldBe false
            verify { roles wasNot Called }
        }

        test("unresolved and mismatched role definitions fail closed") {
            val roles = mockk<RoleResolver>()
            every { roles.resolve(paymentAdapter) } returns null andThen role(accounting)
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { user() }, roles)

            resolver.permissionsFor(userId) shouldBe emptySet()
            resolver.permissionsFor(userId) shouldBe emptySet()
            verify(exactly = 2) { roles.resolve(paymentAdapter) }
        }

        test("a missing role does not suppress grants from another assigned role") {
            val staff = user(roles = setOf(RoleAssignment(paymentAdapter), RoleAssignment(accounting)))
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver { staff },
                    RoleResolver { if (it == accounting) role(accounting, setOf(CommercePermissions.PaymentRecord)) else null },
                )

            resolver.permissionsFor(userId) shouldBe setOf(CommercePermissions.PaymentRecord)
        }

        test("built-in keys are ordinary values and imply no grants") {
            CommerceRoles.Manager shouldBe RoleKey("commerce.manager")
            CommercePermissions.PaymentRecord shouldBe PermissionKey("commerce.payment.record")
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver { user(roles = setOf(RoleAssignment(CommerceRoles.Manager))) },
                    RoleResolver { null },
                )
            userId.can(CommercePermissions.PaymentRecord, resolver) shouldBe false
        }

        test("a service receives only the permissions of its assigned role") {
            val reporter = RoleKey("commerce.payment-reporter")
            val machine = service(roles = setOf(RoleAssignment(reporter)))
            val definition =
                RoleDefinition(
                    reporter,
                    "Payment Reporter",
                    "Reports external payments and refunds",
                    setOf(CommercePermissions.PaymentRecord, CommercePermissions.RefundRecord),
                )
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver { if (it == serviceId) machine else null },
                    RoleResolver { if (it == reporter) definition else null },
                )

            serviceId.can(CommercePermissions.PaymentRecord, resolver) shouldBe true
            serviceId.can(CommercePermissions.RefundRecord, resolver) shouldBe true
            serviceId.can(CommercePermissions.UserManage, resolver) shouldBe false
        }

        test("the same role definition grants permissions to humans and services") {
            val definition = role(permissions = setOf(CommercePermissions.PaymentRecord))
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver {
                        when (it) {
                            userId -> user()
                            serviceId -> service()
                            else -> null
                        }
                    },
                    RoleResolver { if (it == paymentAdapter) definition else null },
                )

            userId.can(CommercePermissions.PaymentRecord, resolver) shouldBe true
            serviceId.can(CommercePermissions.PaymentRecord, resolver) shouldBe true
        }

        test("unknown and disabled services fail closed before role resolution") {
            val roles = mockk<RoleResolver>()
            val resolver =
                RoleBasedPermissionResolver(
                    PrincipalResolver { if (it == serviceId) service(PrincipalStatus.DISABLED) else null },
                    roles,
                )

            resolver.permissionsFor(serviceId) shouldBe emptySet()
            serviceId.can(observePayments, resolver) shouldBe false
            ServiceId(UUID.randomUUID()).can(observePayments, resolver) shouldBe false
            verify { roles wasNot Called }
        }

        test("a service resolver returning a human principal fails closed") {
            val roles = mockk<RoleResolver>()
            val wrongType =
                User(
                    UserId(serviceId.value),
                    "alex",
                    "Alex",
                    null,
                    "Alex",
                    PrincipalStatus.ACTIVE,
                    setOf(RoleAssignment(paymentAdapter)),
                )
            val resolver = RoleBasedPermissionResolver(PrincipalResolver { wrongType }, roles)

            serviceId.can(observePayments, resolver) shouldBe false
            verify { roles wasNot Called }
        }
    })
