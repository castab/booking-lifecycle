package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.offering.OfferingsViolation
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.ValidationViolation

/** Preserves domain and application policy violation codes for the shared HTTP error contract. */
fun offeringsValidationFailed(
    message: String,
    violations: List<OfferingsViolation>,
): CommerceFailure.ValidationFailed = CommerceFailure.ValidationFailed(message, violations.map { ValidationViolation(it.code) })
