package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int

/**
 * Which catalog routes a binding exposes, and how its write routes are authorized.
 *
 * The runtime declares the permission a write requires; the application supplies, through
 * [ReadWrite.accessControl], the authentication filter and the `PermissionResolver` that
 * evaluate it. A binding without writes needs neither.
 */
sealed interface OfferingsHttpAccess {
    /**
     * Only the ordinary reads of the current catalog. Retired management discovery is absent.
     * These reads carry no permission requirement of their own: they are as
     * public as the place the host mounts them.
     */
    data object ReadOnly : OfferingsHttpAccess

    /**
     * Ordinary reads plus protected retired discovery, catalog initialization, and offering/category add, update, retire,
     * and restore routes. Every write requires an authenticated principal that currently holds
     * [CommercePermissions.OfferingsManage], evaluated through [accessControl]: `401` without
     * a principal, `403` without the permission.
     */
    class ReadWrite(
        val accessControl: AccessControl,
    ) : OfferingsHttpAccess {
        override fun toString(): String = "ReadWrite"
    }
}

/**
 * Application-owned route placement for exactly one catalog. [access] decides which routes
 * exist and, for writes, carries their authorization dependency. [tags] are the host's
 * OpenAPI grouping for every route of this binding; when empty, http4k's default grouping
 * applies.
 */
data class OfferingsHttpBinding(
    val catalogId: OfferingsCatalogId,
    val basePath: String,
    val operationIdPrefix: String,
    val access: OfferingsHttpAccess,
    val tags: Set<Tag> = emptySet(),
) {
    init {
        require(
            basePath.startsWith('/') && basePath.length > 1 && !basePath.endsWith('/'),
        ) { "Base path must begin with / and have no trailing /" }
        require(
            basePath.none {
                it == '?' || it == '{' || it == '}' || it == '#'
            },
        ) { "Base path cannot contain query, fragment, or path parameters" }
        require(basePath.split('/').drop(1).all { it.isNotBlank() }) { "Base path cannot contain empty segments" }
        require(
            operationIdPrefix.matches(Regex("[A-Za-z][A-Za-z0-9]*")),
        ) { "Operation ID prefix must be alphanumeric and begin with a letter" }
        require(tags.none { it.name.isBlank() }) { "OpenAPI tag names cannot be blank" }
    }
}

/** Original contract routes for an application to compose into its own http4k contract. */
class OfferingsHttpCapability internal constructor(
    val contractRoutes: List<ContractRoute>,
)

fun offeringsHttpCapability(
    context: CommerceRuntimeContext,
    binding: OfferingsHttpBinding,
): OfferingsHttpCapability {
    val catalogId = binding.catalogId
    val base = binding.basePath
    val transactor = context.transactor
    val repository = context.offeringsSnapshotRepository
    val getCatalog = GetOfferingsCatalog(transactor, repository)
    val listCategories = ListOfferingCategories(getCatalog)
    val getCategory = GetOfferingCategory(getCatalog)
    val listCategoryOfferings = ListCategoryOfferings(getCatalog)
    val listOfferings = ListOfferings(getCatalog)
    val getOffering = GetOffering(getCatalog)
    val createCatalog = CreateOfferingsCatalog(transactor, repository)
    val addCategory = AddOfferingCategory(transactor, repository)
    val addOffering = AddOffering(transactor, repository)

    val catalogBody = jsonBody(OfferingsCatalogDto.serializer())
    val categoriesBody = jsonBody(CategoriesDto.serializer())
    val categoryBody = jsonBody(CategoryDto.serializer())
    val categoryOfferingsBody = jsonBody(CategoryOfferingsDto.serializer())
    val offeringsBody = jsonBody(OfferingsDto.serializer())
    val offeringBody = jsonBody(OfferingResultDto.serializer())
    val categoryRequest = jsonBody(AddOfferingCategoryDto.serializer())
    val offeringRequest = jsonBody(AddOfferingDto.serializer())
    val offeringMutationRequest = jsonBody(OfferingMutationDto.serializer())
    val categoryMutationRequest = jsonBody(OfferingCategoryMutationDto.serializer())
    val revisionBody = jsonBody(CatalogRevisionDto.serializer())
    val retiredOfferingsBody = jsonBody(RetiredOfferingsDto.serializer())
    val retiredCategoriesBody = jsonBody(RetiredCategoriesDto.serializer())
    val errorBody = jsonBody(ErrorResponse.serializer())
    val validationErrorBody = jsonBody(ValidationErrorResponse.serializer())
    val expectedRevisionQuery = Query.int().required("expectedRevision", "Catalog revision observed by the caller")

    fun expectedRevisionFromQuery(request: Request): OfferingsRevision {
        if (request.queries("expectedRevision").size > 1) {
            throw LensFailure(Invalid(expectedRevisionQuery.meta), target = request)
        }
        return validating { OfferingsRevision.of(expectedRevisionQuery(request)) }
    }
    val categoryPath = Path.of("categoryKey")
    val offeringPath = Path.of("offeringKey")
    val sampleCategory = OfferingCategoryDto("choice", "Choice", "An optional choice", 0, 2)
    val samplePrice = OfferingPriceDto("PER_QUANTITY", "0.75", "USD", "guest")
    val sampleOffering =
        OfferingDto(
            "item",
            "choice",
            "Item",
            "An item",
            samplePrice,
            OfferingSelectionStateDto.ENABLED,
            OfferingAvailabilityDto.AVAILABLE,
            "Popular",
            "Back this fall",
        )
    val sampleOfferingMutation =
        OfferingMutationDto(
            3,
            "choice",
            "Item",
            "An item",
            samplePrice,
            OfferingSelectionStateDto.ENABLED,
            OfferingAvailabilityDto.AVAILABLE,
            "Popular",
            "Back this fall",
        )
    val sampleCategoryMutation = OfferingCategoryMutationDto(3, "Choice", "An optional choice", 0, 2)
    val sampleAddOffering =
        AddOfferingDto(
            2,
            "item",
            "choice",
            "Item",
            "An item",
            samplePrice,
            OfferingSelectionStateDto.ENABLED,
            OfferingAvailabilityDto.AVAILABLE,
            "Popular",
            "Back this fall",
        )
    val sampleAddCategory = AddOfferingCategoryDto(1, "choice", "Choice", "An optional choice", 0, 2)
    // Examples follow one coherent history: initialization creates an empty r1, adding the
    // category creates r2, and adding the offering creates r3, which the reads return.
    val initializedCatalog = OfferingsCatalogDto(catalogId.value.toString(), 1, null, emptyList())
    val sampleCatalog =
        OfferingsCatalogDto(
            catalogId.value.toString(),
            3,
            2,
            listOf(CatalogCategoryDto("choice", "Choice", "An optional choice", 0, 2, listOf(sampleOffering))),
        )

    fun RouteMetaDsl.errors(vararg statuses: Status) {
        statuses.forEach { status ->
            val category = ErrorCategory.entries.first { it.status == status }
            if (category == ErrorCategory.VALIDATION_FAILED) {
                returning(status, validationErrorBody to ValidationErrorResponse(category.code, "Request failed"))
            } else {
                returning(status, errorBody to ErrorResponse(category.code, "Request failed"))
            }
        }
    }

    fun RouteMetaDsl.describe(
        id: String,
        title: String,
    ) {
        operationId = "${binding.operationIdPrefix}$id"
        summary = title
        tags += binding.tags
    }

    val routes =
        mutableListOf<ContractRoute>(
            (
                base meta {
                    describe("GetCatalog", "Get the latest offerings catalog")
                    returning(Status.OK, catalogBody to sampleCatalog)
                    errors(Status.NOT_FOUND)
                } bindContract Method.GET to { _: Request ->
                    Response(Status.OK).with(catalogBody of getCatalog(catalogId).dto())
                }
            ),
            (
                "$base/categories" meta {
                    describe("ListCategories", "List categories in snapshot order")
                    returning(Status.OK, categoriesBody to CategoriesDto(3, listOf(sampleCategory)))
                    errors(Status.NOT_FOUND)
                } bindContract Method.GET to { _: Request ->
                    Response(Status.OK).with(categoriesBody of listCategories(catalogId).categoriesDto())
                }
            ),
            (
                "$base/categories" / categoryPath meta {
                    describe("GetCategory", "Get a category from the latest catalog")
                    returning(Status.OK, categoryBody to CategoryDto(3, sampleCategory))
                    errors(Status.UNPROCESSABLE_ENTITY, Status.NOT_FOUND)
                } bindContract Method.GET to { key: String ->
                    { _: Request ->
                        val categoryKey = validating { OfferingCategoryKey(key) }
                        Response(Status.OK).with(categoryBody of getCategory(catalogId, categoryKey).categoryDto())
                    }
                }
            ),
            (
                "$base/categories" / categoryPath / "offerings" meta {
                    describe("ListCategoryOfferings", "List a category's offerings in snapshot order")
                    returning(Status.OK, categoryOfferingsBody to CategoryOfferingsDto(3, sampleCategory, listOf(sampleOffering)))
                    errors(Status.UNPROCESSABLE_ENTITY, Status.NOT_FOUND)
                } bindContract Method.GET to { key: String, _: String ->
                    { _: Request ->
                        val categoryKey = validating { OfferingCategoryKey(key) }
                        Response(Status.OK).with(
                            categoryOfferingsBody of listCategoryOfferings(catalogId, categoryKey).categoryOfferingsDto(),
                        )
                    }
                }
            ),
            (
                "$base/offerings" meta {
                    describe("ListOfferings", "List offerings in snapshot order")
                    returning(Status.OK, offeringsBody to OfferingsDto(3, listOf(sampleOffering)))
                    errors(Status.NOT_FOUND)
                } bindContract Method.GET to { _: Request ->
                    Response(Status.OK).with(offeringsBody of listOfferings(catalogId).offeringsDto())
                }
            ),
            (
                "$base/offerings" / offeringPath meta {
                    describe("GetOffering", "Get an offering from the latest catalog")
                    returning(Status.OK, offeringBody to OfferingResultDto(3, sampleOffering))
                    errors(Status.UNPROCESSABLE_ENTITY, Status.NOT_FOUND)
                } bindContract Method.GET to { key: String ->
                    { _: Request ->
                        val offeringKey = validating { OfferingKey(key) }
                        Response(Status.OK).with(offeringBody of getOffering(catalogId, offeringKey).offeringDto())
                    }
                }
            ),
        )

    val access = binding.access
    if (access is OfferingsHttpAccess.ReadWrite) {
        val manage = access.accessControl.requirePermission(CommercePermissions.OfferingsManage)
        // Keep lifecycle discovery outside the item-key paths so even the key "retired" remains addressable.
        routes +=
            "$base/retired/offerings" meta {
                describe("ListRetiredOfferings", "List retired identities and their last offering representations")
                returning(Status.OK, retiredOfferingsBody to RetiredOfferingsDto(4, listOf(RetiredOfferingDto(3, sampleOffering))))
                errors(Status.UNAUTHORIZED, Status.FORBIDDEN, Status.NOT_FOUND)
            } bindContract Method.GET to
            manage.then { _: Request ->
                Response(Status.OK).with(
                    retiredOfferingsBody of ListRetiredOfferings(transactor, repository)(catalogId).retiredOfferingsDto(),
                )
            }
        routes +=
            "$base/retired/categories" meta {
                describe("ListRetiredCategories", "List retired identities and their last category representations")
                returning(Status.OK, retiredCategoriesBody to RetiredCategoriesDto(5, listOf(RetiredCategoryDto(4, sampleCategory))))
                errors(Status.UNAUTHORIZED, Status.FORBIDDEN, Status.NOT_FOUND)
            } bindContract Method.GET to
            manage.then { _: Request ->
                Response(Status.OK).with(
                    retiredCategoriesBody of ListRetiredCategories(transactor, repository)(catalogId).retiredCategoriesDto(),
                )
            }

        routes +=
            base meta {
                describe("CreateCatalog", "Initialize an empty offerings catalog")
                returning(Status.CREATED, catalogBody to initializedCatalog)
                errors(Status.UNAUTHORIZED, Status.FORBIDDEN, Status.CONFLICT)
            } bindContract Method.POST to
            manage.then { _: Request ->
                Response(Status.CREATED).with(catalogBody of createCatalog(catalogId).dto())
            }
        routes +=
            "$base/categories" meta {
                describe("AddCategory", "Append a category in a successor catalog revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(categoryRequest to sampleAddCategory)
                returning(Status.CREATED, categoryBody to CategoryDto(2, sampleCategory))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.POST to
            manage.then { request: Request ->
                val body = categoryRequest(request)
                val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                val category = body.toDomain()
                Response(Status.CREATED).with(categoryBody of addCategory(catalogId, expectedRevision, category).categoryDto())
            }
        routes +=
            "$base/offerings" meta {
                describe("AddOffering", "Append an offering in a successor catalog revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(offeringRequest to sampleAddOffering)
                returning(Status.CREATED, offeringBody to OfferingResultDto(3, sampleOffering))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.POST to
            manage.then { request: Request ->
                val body = offeringRequest(request)
                val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                val offering = body.toDomain()
                Response(Status.CREATED).with(offeringBody of addOffering(catalogId, expectedRevision, offering).offeringDto())
            }
        routes +=
            "$base/offerings" / offeringPath meta {
                describe("UpdateOffering", "Update an offering through a successor revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(offeringMutationRequest to sampleOfferingMutation)
                returning(Status.OK, offeringBody to OfferingResultDto(4, sampleOffering))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.PUT to { key: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingKey(key) }
                    val body = offeringMutationRequest(request)
                    val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                    val value = body.toDomain(identity)
                    Response(Status.OK).with(
                        offeringBody of
                            UpdateOffering(
                                transactor,
                                repository,
                            )(
                                catalogId,
                                expectedRevision,
                                identity,
                                value.category,
                                value.displayName,
                                value.description,
                                value.price,
                                value.selectionState,
                                value.availability,
                                value.badge,
                                value.statusNote,
                            ).offeringDto(),
                    )
                }
            }

        routes +=
            "$base/offerings" / offeringPath meta {
                describe("RetireOffering", "Retire an offering through a successor revision")
                preFlightExtraction = PreFlightExtraction.None
                queries += expectedRevisionQuery
                returning(Status.OK, revisionBody to CatalogRevisionDto(4))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.DELETE to { key: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingKey(key) }
                    val expectedRevision = expectedRevisionFromQuery(request)
                    val successor = RetireOffering(transactor, repository)(catalogId, expectedRevision, identity)
                    Response(Status.OK).with(revisionBody of CatalogRevisionDto(successor.revision.number))
                }
            }

        routes +=
            "$base/offerings" / offeringPath / "restore" meta {
                describe("RestoreOffering", "Restore an offering through a successor revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(offeringMutationRequest to sampleOfferingMutation)
                returning(Status.OK, offeringBody to OfferingResultDto(4, sampleOffering))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.POST to { key: String, _: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingKey(key) }
                    val body = offeringMutationRequest(request)
                    val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                    val value = body.toDomain(identity)
                    Response(Status.OK).with(
                        offeringBody of
                            RestoreOffering(
                                transactor,
                                repository,
                            )(
                                catalogId,
                                expectedRevision,
                                identity,
                                value.category,
                                value.displayName,
                                value.description,
                                value.price,
                                value.selectionState,
                                value.availability,
                                value.badge,
                                value.statusNote,
                            ).offeringDto(),
                    )
                }
            }

        routes +=
            "$base/categories" / categoryPath meta {
                describe("UpdateCategory", "Update a category through a successor revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(categoryMutationRequest to sampleCategoryMutation)
                returning(Status.OK, categoryBody to CategoryDto(4, sampleCategory))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.PUT to { key: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingCategoryKey(key) }
                    val body = categoryMutationRequest(request)
                    val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                    val value = body.toDomain(identity)
                    Response(Status.OK).with(
                        categoryBody of
                            UpdateOfferingCategory(
                                transactor,
                                repository,
                            )(
                                catalogId,
                                expectedRevision,
                                identity,
                                value.displayName,
                                value.description,
                                value.minimumSelections,
                                value.maximumSelections,
                            ).categoryDto(),
                    )
                }
            }

        routes +=
            "$base/categories" / categoryPath meta {
                describe("RetireCategory", "Retire a category through a successor revision")
                preFlightExtraction = PreFlightExtraction.None
                queries += expectedRevisionQuery
                returning(Status.OK, revisionBody to CatalogRevisionDto(4))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.DELETE to { key: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingCategoryKey(key) }
                    val expectedRevision = expectedRevisionFromQuery(request)
                    val successor = RetireOfferingCategory(transactor, repository)(catalogId, expectedRevision, identity)
                    Response(Status.OK).with(revisionBody of CatalogRevisionDto(successor.revision.number))
                }
            }

        routes +=
            "$base/categories" / categoryPath / "restore" meta {
                describe("RestoreCategory", "Restore a category through a successor revision")
                preFlightExtraction = PreFlightExtraction.IgnoreBody
                receiving(categoryMutationRequest to sampleCategoryMutation)
                returning(Status.OK, categoryBody to CategoryDto(4, sampleCategory))
                errors(
                    Status.UNAUTHORIZED,
                    Status.FORBIDDEN,
                    Status.BAD_REQUEST,
                    Status.UNPROCESSABLE_ENTITY,
                    Status.NOT_FOUND,
                    Status.CONFLICT,
                )
            } bindContract Method.POST to { key: String, _: String ->
                manage.then { request: Request ->
                    val identity = validating { OfferingCategoryKey(key) }
                    val body = categoryMutationRequest(request)
                    val expectedRevision = validating { OfferingsRevision.of(body.expectedRevision) }
                    val value = body.toDomain(identity)
                    Response(Status.OK).with(
                        categoryBody of
                            RestoreOfferingCategory(
                                transactor,
                                repository,
                            )(
                                catalogId,
                                expectedRevision,
                                identity,
                                value.displayName,
                                value.description,
                                value.minimumSelections,
                                value.maximumSelections,
                            ).categoryDto(),
                    )
                }
            }
    }
    return OfferingsHttpCapability(routes)
}
