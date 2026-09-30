package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.FinancialDocument
import java.time.Instant

/** One persisted immutable financial-document version and its database-assigned creation instant. */
class FinancialDocumentVersion private constructor(
    val document: FinancialDocument,
    val createdAt: Instant,
) {
    internal companion object {
        fun from(
            document: FinancialDocument,
            createdAt: Instant,
        ): FinancialDocumentVersion = FinancialDocumentVersion(document, createdAt)
    }
}
