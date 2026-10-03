package io.github.castab.commerce.deposit

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Explicit financial terms approved against an exact document snapshot; there are no defaults. */
sealed class DepositTerms private constructor() {
    /** A positive exact amount. Approval also checks currency agreement and the document total. */
    data class Fixed(
        val amount: Money,
    ) : DepositTerms() {
        init {
            require(amount.amount.signum() > 0) { "A fixed deposit amount must be positive" }
        }
    }

    /** An exact decimal percentage in (0, 100]. */
    data class Percentage(
        val percentage: BigDecimal,
    ) : DepositTerms() {
        init {
            require(percentage.signum() > 0 && percentage <= BigDecimal("100")) {
                "A deposit percentage must be greater than zero and at most 100"
            }
        }
    }

    /**
     * Resolves these terms against [document]'s total. Percentages use exact arithmetic and
     * HALF_UP rounding to the currency's minor units. Fixed amounts retain their precision.
     * Rejects currency mismatches, currencies without minor-unit metadata for percentages,
     * results rounding to zero, and amounts exceeding the approval total; never clamps.
     */
    fun resolve(document: FinancialDocument): Money {
        val resolved =
            when (this) {
                is Fixed -> amount
                is Percentage -> {
                    val units = document.currency.defaultFractionDigits
                    require(units >= 0) { "Deposit percentages require currency minor-unit metadata" }
                    Money(
                        document.total.amount
                            .multiply(percentage)
                            .movePointLeft(2)
                            .setScale(units, RoundingMode.HALF_UP),
                        document.currency,
                    )
                }
            }
        require(resolved.currency == document.currency) { "Deposit and approval document currencies must match" }
        require(resolved.amount.signum() > 0) { "The resolved deposit amount must be positive" }
        require(resolved.amount <= document.total.amount) { "The deposit amount must not exceed the approval document total" }
        return resolved
    }
}
