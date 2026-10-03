package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.offering.OfferingsSnapshotReference

/** The last representation of a retired key, with the catalog revision it was last present in. */
data class RetiredCatalogValue<T>(
    val lastSeen: OfferingsSnapshotReference,
    val value: T,
)
