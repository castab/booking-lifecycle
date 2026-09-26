package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.LensFailure

/**
 * The body of every commerce error response.
 *
 * ```json
 * {"code": "validation_failed", "message": "Financial document 5f0c6a7e-... must contain at least one line item"}
 * ```
 *
 * [code] is stable and machine-readable; [message] is for people and may change.
 */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

/** The error categories of the commerce HTTP contract, each with its status and code. */
enum class ErrorCategory(
    val status: Status,
    val code: String,
) {
    /** The body or a parameter could not be read: invalid JSON, a missing field, an unparsable identifier. */
    MALFORMED_REQUEST(Status.BAD_REQUEST, "malformed_request"),

    /** The request was readable, but a value is invalid. */
    VALIDATION_FAILED(Status.UNPROCESSABLE_ENTITY, "validation_failed"),

    /** The resource or route does not exist. */
    NOT_FOUND(Status.NOT_FOUND, "not_found"),

    /** The request conflicts with current state. */
    CONFLICT(Status.CONFLICT, "conflict"),

    /** The resource's current phase or stage has no such transition. */
    ILLEGAL_TRANSITION(Status.CONFLICT, "illegal_transition"),

    /** Applying the request would violate a domain invariant. */
    INVARIANT_VIOLATED(Status.UNPROCESSABLE_ENTITY, "invariant_violated"),

    /** Anything unexpected. Its cause is logged, never returned. */
    INTERNAL_FAILURE(Status.INTERNAL_SERVER_ERROR, "internal_failure"),
}

private val errorBody = jsonBody(ErrorResponse.serializer())
private val logger = KotlinLogging.logger {}

/** Builds the error response for [category]. */
fun errorResponse(
    category: ErrorCategory,
    message: String,
): Response = Response(category.status).with(errorBody of ErrorResponse(category.code, message))

/** The category a [CommerceFailure] is reported as. */
fun CommerceFailure.category(): ErrorCategory =
    when (this) {
        is CommerceFailure.ValidationFailed -> ErrorCategory.VALIDATION_FAILED
        is CommerceFailure.NotFound -> ErrorCategory.NOT_FOUND
        is CommerceFailure.Conflict -> ErrorCategory.CONFLICT
        is CommerceFailure.IllegalTransition -> ErrorCategory.ILLEGAL_TRANSITION
        is CommerceFailure.InvariantViolated -> ErrorCategory.INVARIANT_VIOLATED
    }

/**
 * Turns every failure into the commerce error contract.
 *
 * - An http4k [LensFailure] becomes `malformed_request`, naming the unreadable inputs.
 * - A [CommerceFailure] becomes its [category], carrying its caller-safe message.
 * - Any other exception becomes `internal_failure` with a generic message. It is logged
 *   here and never described to the caller, so SQL, stack traces, and implementation
 *   detail cannot leak.
 * - A 404 without a body (no route matched) gets the same `not_found` shape.
 */
val CommerceErrorHandling =
    Filter { next ->
        { request ->
            try {
                val response = next(request)
                if (response.status == Status.NOT_FOUND && response.body.length == 0L) {
                    errorResponse(ErrorCategory.NOT_FOUND, "No resource at ${request.uri.path}")
                } else {
                    response
                }
            } catch (e: LensFailure) {
                val inputs = e.failures.joinToString { "${it.meta.location} '${it.meta.name}'" }
                errorResponse(ErrorCategory.MALFORMED_REQUEST, "Malformed request: $inputs")
            } catch (e: CommerceFailure) {
                errorResponse(e.category(), e.message ?: e.category().code)
            } catch (e: Exception) {
                logger.error(e) { "event=request_failed method=${request.method} path=${request.uri.path}" }
                errorResponse(ErrorCategory.INTERNAL_FAILURE, "The request could not be completed")
            }
        }
    }
