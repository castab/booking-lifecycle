package io.github.castab.commerce.runtime.authorization

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionGroup
import io.github.castab.commerce.staff.PermissionKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val catering = PermissionGroup("catering.inquiries")
private val assignInquiries =
    PermissionDefinition(PermissionKey("catering.inquiries.assign"), "Assign inquiries", "Assign inquiries to staff.", catering)
private val readInquiries =
    PermissionDefinition(PermissionKey("catering.inquiries.read"), "Read inquiries", "View catering inquiries.", catering)
private val calendar =
    PermissionDefinition(
        PermissionKey("events.calendar.manage"),
        "Manage the event calendar",
        "Create and move calendar events.",
        PermissionGroup("events"),
    )
private val applicationPermissions = listOf(readInquiries, calendar, assignInquiries)

class PermissionCatalogSpec :
    FunSpec({
        test("the runtime-only catalog describes every CommercePermissions key") {
            val catalog = PermissionCatalog(commercePermissionDefinitions)
            catalog.definitions.size shouldBe 12
            listOf(
                CommercePermissions.BookingRead,
                CommercePermissions.BookingModify,
                CommercePermissions.FinancialDocumentRead,
                CommercePermissions.FinancialDocumentCreate,
                CommercePermissions.OfferingsManage,
                CommercePermissions.PaymentRecord,
                CommercePermissions.RefundRecord,
                CommercePermissions.PrincipalRead,
                CommercePermissions.PrincipalManage,
                CommercePermissions.RoleRead,
                CommercePermissions.RoleManage,
                CommercePermissions.RoleAssign,
            ).forEach { key -> (key in catalog) shouldBe true }
            catalog.find(CommercePermissions.RoleRead)!!.group shouldBe PermissionGroup("commerce.roles")
            catalog.definitions.forEach { definition ->
                definition.displayName.isNotBlank() shouldBe true
                definition.description.isNotBlank() shouldBe true
            }
        }

        test("runtime and application permissions compose into one catalog ordered by key") {
            val catalog = PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)

            catalog.definitions.size shouldBe commercePermissionDefinitions.size + applicationPermissions.size
            catalog.definitions.map { it.key.value } shouldBe catalog.definitions.map { it.key.value }.sorted()
            catalog.definitions.take(2) shouldContainExactly listOf(assignInquiries, readInquiries)
            catalog.definitions.last() shouldBe calendar
            catalog.find(assignInquiries.key) shouldBe assignInquiries
            catalog.find(CommercePermissions.RoleManage) shouldBe
                commercePermissionDefinitions.single { it.key == CommercePermissions.RoleManage }
            (calendar.key in catalog) shouldBe true
            catalog.find(PermissionKey("definitely.not.a.permission")).shouldBeNull()
            (PermissionKey("definitely.not.a.permission") in catalog) shouldBe false
        }

        test("ordering is independent of registration order") {
            val forward = PermissionCatalog(applicationPermissions)
            val reversed = PermissionCatalog(applicationPermissions.reversed())

            forward.definitions shouldBe reversed.definitions
            forward.definitions shouldContainExactly listOf(assignInquiries, readInquiries, calendar)
        }

        test("duplicate keys are rejected and named, even with identical metadata") {
            shouldThrow<IllegalArgumentException> {
                PermissionCatalog.of(
                    commercePermissionDefinitions,
                    listOf(
                        commercePermissionDefinitions.first {
                            it.key ==
                                CommercePermissions.RoleRead
                        },
                    ),
                )
            }.message shouldBe "Duplicate permission keys: commerce.role.read"
            shouldThrow<IllegalArgumentException> {
                PermissionCatalog(
                    listOf(calendar, readInquiries, calendar.copy(displayName = "Edit the calendar"), readInquiries, assignInquiries),
                )
            }.message shouldBe "Duplicate permission keys: catering.inquiries.read, events.calendar.manage"
        }

        test("malformed definitions fail before a catalog can be built") {
            shouldThrow<IllegalArgumentException> { PermissionKey("Users.Create") }
            shouldThrow<IllegalArgumentException> { PermissionGroup("") }
            shouldThrow<IllegalArgumentException> { calendar.copy(displayName = " ") }
            shouldThrow<IllegalArgumentException> { calendar.copy(description = "") }
        }

        test("the catalog is an immutable copy") {
            val supplied = applicationPermissions.toMutableList()
            val catalog = PermissionCatalog(supplied)
            supplied.clear()

            catalog.definitions.size shouldBe 3
            shouldThrow<UnsupportedOperationException> { (catalog.definitions as MutableList).clear() }
        }

        test("the revision is a deterministic digest of the catalog's contents") {
            val catalog = PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)

            catalog.revision shouldMatch Regex("sha256:[0-9a-f]{64}")
            PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions).revision shouldBe catalog.revision
            PermissionCatalog.of(applicationPermissions.reversed(), commercePermissionDefinitions.reversed()).revision shouldBe
                catalog.revision
            PermissionCatalog(emptyList()).revision shouldBe PermissionCatalog(emptyList()).revision

            val added = PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)
            val variants =
                listOf(
                    PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions - calendar),
                    PermissionCatalog.of(
                        commercePermissionDefinitions,
                        applicationPermissions +
                            PermissionDefinition(
                                PermissionKey("events.calendar.read"),
                                "Read the calendar",
                                "View events.",
                                calendar.group,
                            ),
                    ),
                    PermissionCatalog.of(
                        commercePermissionDefinitions,
                        applicationPermissions - calendar + calendar.copy(displayName = "Edit"),
                    ),
                    PermissionCatalog.of(
                        commercePermissionDefinitions,
                        applicationPermissions - calendar + calendar.copy(description = "Edit."),
                    ),
                    PermissionCatalog.of(
                        commercePermissionDefinitions,
                        applicationPermissions - calendar + calendar.copy(group = PermissionGroup("calendar")),
                    ),
                    PermissionCatalog.of(
                        commercePermissionDefinitions,
                        applicationPermissions - calendar + calendar.copy(key = PermissionKey("events.calendar.edit")),
                    ),
                    PermissionCatalog(commercePermissionDefinitions),
                )
            variants.forEach { it.revision shouldNotBe added.revision }
            variants.map { it.revision }.toSet().size shouldBe variants.size
        }

        test("field boundaries are part of the revision") {
            val group = PermissionGroup("a")
            val first = PermissionDefinition(PermissionKey("a.b"), "x y", "z", group)
            val shifted = PermissionDefinition(PermissionKey("a.b"), "x", "y z", group)

            PermissionCatalog(listOf(first)).revision shouldNotBe PermissionCatalog(listOf(shifted)).revision
        }

        test("role grants are validated against the catalog and unknown keys are named in order") {
            val catalog = PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)

            catalog.validate(setOf(CommercePermissions.RoleRead, assignInquiries.key))
            catalog.validate(emptySet())
            shouldThrow<CommerceFailure.ValidationFailed> {
                catalog.validate(setOf(PermissionKey("zz.unknown"), CommercePermissions.RoleRead, PermissionKey("aa.unknown")))
            }.message shouldBe "Unknown permissions: aa.unknown, zz.unknown"
        }

        test("concurrent readers observe the same catalog") {
            val catalog = PermissionCatalog.of(commercePermissionDefinitions, applicationPermissions)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val results =
                    (1..64)
                        .map {
                            executor.submit<Pair<String, Int>> {
                                catalog.revision to catalog.definitions.count { it.key in catalog && catalog.find(it.key) == it }
                            }
                        }.map { it.get(10, TimeUnit.SECONDS) }
                results.toSet() shouldBe setOf(catalog.revision to catalog.definitions.size)
            } finally {
                executor.shutdownNow()
            }
        }
    })
