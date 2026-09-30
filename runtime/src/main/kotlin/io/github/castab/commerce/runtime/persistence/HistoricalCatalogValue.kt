package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.offering.OfferingsSnapshotReference

/** One stored representation at the exact catalog revision that contains it. */
data class HistoricalCatalogValue<T>(
    val reference: OfferingsSnapshotReference,
    val value: T,
)
