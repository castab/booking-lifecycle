package io.github.castab.commerce.deposit

/** Position in a lineage's immutable requirement stream, independent of document versions. */
class DepositRequirementRevision private constructor(
    val number: Int,
) : Comparable<DepositRequirementRevision> {
    /** The immediate successor; throws [ArithmeticException] on overflow. */
    fun next(): DepositRequirementRevision = of(Math.addExact(number, 1))

    /** The immediate predecessor, absent only for revision one. */
    val previous: DepositRequirementRevision?
        get() = if (number == 1) null else of(number - 1)

    override fun compareTo(other: DepositRequirementRevision): Int = number.compareTo(other.number)

    override fun equals(other: Any?): Boolean = other is DepositRequirementRevision && number == other.number

    override fun hashCode(): Int = number

    override fun toString(): String = "r$number"

    companion object {
        /** The first approved requirement's revision. */
        @JvmField
        val INITIAL: DepositRequirementRevision = DepositRequirementRevision(1)

        /** Restores a positive revision number. */
        @JvmStatic
        fun of(number: Int): DepositRequirementRevision {
            require(number >= 1) { "A deposit requirement revision must be at least one" }
            return DepositRequirementRevision(number)
        }
    }
}
