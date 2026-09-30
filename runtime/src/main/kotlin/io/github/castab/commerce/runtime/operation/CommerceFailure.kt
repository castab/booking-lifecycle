package io.github.castab.commerce.runtime.operation

import java.util.Collections

/** A stable identifier for one validation problem. Its diagnostic text is the enclosing failure message. */
data class ValidationViolation(
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "Validation violation code cannot be blank" }
    }
}

/**
 * An expected failure of an operation (a use case such as issuing an invoice or recording
 * a payment).
 *
 * Operations throw these, so a failure inside a transaction also rolls it back. The HTTP
 * layer maps each kind to one status and error code. The [message] is returned to
 * callers: it must describe the problem in domain terms and must never contain SQL,
 * stack traces, credentials, or other implementation detail. Anything that is not a
 * [CommerceFailure] is treated as an internal failure and is never described to callers.
 */
sealed class CommerceFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Well-formed input whose values are invalid, for example a document with no line items. */
    class ValidationFailed private constructor(
        message: String,
        cause: Throwable?,
        violations: List<ValidationViolation>,
    ) : CommerceFailure(message, cause) {
        val violations: List<ValidationViolation> = Collections.unmodifiableList(ArrayList(violations))

        constructor(message: String, cause: Throwable? = null) : this(message, cause, emptyList())

        constructor(
            message: String,
            violations: List<ValidationViolation>,
            cause: Throwable? = null,
        ) : this(message, cause, violations) {
            require(violations.isNotEmpty()) { "Structured validation failure must have violations" }
        }
    }

    /** A referenced resource does not exist. */
    class NotFound(
        message: String,
    ) : CommerceFailure(message)

    /** The request conflicts with current state, for example an identifier that already exists. */
    class Conflict(
        message: String,
        cause: Throwable? = null,
    ) : CommerceFailure(message, cause)

    /**
     * The resource's current lifecycle phase or stage has no such transition, for example
     * completing a booking that is still a quote. The domain expresses legal transitions in
     * its types; this is how the runtime reports a request for one that does not exist.
     */
    class IllegalTransition(
        message: String,
    ) : CommerceFailure(message)

    /**
     * The request is valid on its own, but applying it would violate a domain invariant,
     * for example allocating more than a payment's unallocated amount.
     */
    class InvariantViolated(
        message: String,
        cause: Throwable? = null,
    ) : CommerceFailure(message, cause)
}

/**
 * Runs [block], which translates caller input into domain values, and reports a domain
 * validation failure (`require`, [IllegalArgumentException]) as
 * [CommerceFailure.ValidationFailed] carrying the domain's message.
 *
 * Wrap only the translation of input. Wrapping broader code would misreport programming
 * errors as caller mistakes.
 */
inline fun <T> validating(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw CommerceFailure.ValidationFailed(e.message ?: "Invalid request", e)
    }
