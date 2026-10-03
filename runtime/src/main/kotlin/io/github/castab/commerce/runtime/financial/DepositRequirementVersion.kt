package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import java.time.Instant

/** One immutable deposit-requirement revision and its database-assigned creation instant. */
class DepositRequirementVersion private constructor(
    val requirement: DepositRequirement,
    val createdAt: Instant,
) {
    internal companion object {
        fun from(
            requirement: DepositRequirement,
            createdAt: Instant,
        ): DepositRequirementVersion = DepositRequirementVersion(requirement, createdAt)
    }
}
