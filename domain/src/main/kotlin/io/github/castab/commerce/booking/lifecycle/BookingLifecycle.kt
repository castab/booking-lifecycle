package io.github.castab.commerce.booking.lifecycle

/**
 * The type-level booking lifecycle protocol.
 *
 * This hierarchy defines the phases of a booking lifecycle and, through the transition
 * functions declared on each phase, the legal edges between them:
 *
 * ```text
 * InitialRequest ──toQuote()──► Quote ──toBooking()──► Booked ──complete()──► Completed
 *        │                        │                      │
 *        └──cancel()──────────────┴──cancel()────────────┴──cancel()────────► Cancelled
 * ```
 *
 * A lifecycle may begin at either [Active.InitialRequest] or [Active.Quote]. There is no
 * library-owned factory or initial value: a lifecycle begins when an application
 * constructs a model that implements one of those two phases.
 *
 * ## Application models inhabit phases
 *
 * Consuming applications do not store a lifecycle value inside their models. Their
 * domain types implement a phase interface directly. Every type below, including
 * `CustomerId`, belongs to an example catering application, not to this library:
 *
 * ```kotlin
 * // Application-owned identity; commerce-domain defines no customer concept.
 * data class CustomerId(val value: UUID)
 *
 * data class CateringQuote(
 *     val customerId: CustomerId,
 *     val total: BigDecimal,
 * ) : BookingLifecycle.Active.Quote {
 *     override fun toBooking(): CateringBooking = CateringBooking(customerId, total)
 *     override fun cancel(): DeclinedCateringQuote = DeclinedCateringQuote(customerId)
 * }
 * ```
 *
 * `CateringQuote` does not *contain* the `Quote` phase. It *is* a concrete application
 * representation of the `Quote` phase. Advancing the lifecycle means transforming one
 * lifecycle-typed application model into another, not mutating a state field. The
 * library defines no booking record, customer, contact, or location: what a booking
 * contains, and whom it concerns, is the application's decision.
 *
 * ## Edges, not policies
 *
 * Transition functions represent legal lifecycle edges, not the business prerequisites
 * necessary to traverse those edges. The library defines what may legally follow a phase.
 * The implementing application defines whether, when, and how the transition occurs,
 * and what data the next phase contains. Every transition function is abstract.
 *
 * Illegal edges are absent from the API rather than rejected at runtime. For example,
 * [Active.InitialRequest] declares no `complete()`, and [Terminal] phases declare no
 * transitions at all.
 *
 * Implementations may narrow each transition's return type to their own concrete type
 * (for example, `override fun toBooking(): CateringBooking`). Callers holding the concrete
 * type then receive concrete types back without casts.
 *
 * ## Sealed taxonomy, open phases
 *
 * [BookingLifecycle], [Active], and [Terminal] are sealed. The lifecycle taxonomy is closed,
 * and `when` expressions over a [BookingLifecycle] or an [Active] are exhaustive without an
 * `else` branch. The five phase interfaces are deliberately *not* sealed, so types in
 * any module can implement them.
 *
 * ## Intentional limits
 *
 * The protocol provides the canonical lifecycle API. It does not control all application
 * code. An application can still construct a terminal model directly, and nothing in the
 * type system stops a single class from implementing more than one phase. Phases are
 * intended to be mutually exclusive, and each concrete application type should implement
 * exactly one of them.
 */
sealed interface BookingLifecycle {
    /**
     * Classification of the phases in which a booking lifecycle has not yet reached an
     * outcome.
     *
     * The active phases are exactly [InitialRequest], [Quote], and [Booked]. Each of them
     * may legally be cancelled.
     */
    sealed interface Active : BookingLifecycle {
        /**
         * A request for a booking that has not yet become an official quote.
         *
         * **Active.** One of the two lifecycle entry points, and the generic pre-quote phase.
         * The adopting application decides what begins it, for example an inquiry submitted
         * through its own channels.
         *
         * Legal transitions:
         * - [toQuote]: `InitialRequest → Quote`
         * - [cancel]: `InitialRequest → Cancelled`
         *
         * The implementing type owns all of its data (for example customer details,
         * selections, notes, or a requested date). The lifecycle requires none of it. This
         * phase is not a financial estimate: an application may issue a
         * `FinancialDocument.Estimate` while a booking is in this phase, but relating the two
         * is application policy.
         */
        interface InitialRequest : Active {
            /**
             * Represents the legal lifecycle transition from an initial request to an issued
             * quote.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun toQuote(): Quote

            /**
             * Represents the legal lifecycle transition from an initial request to a cancelled
             * outcome, meaning the request ended without becoming a quote.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun cancel(): Terminal.Cancelled
        }

        /**
         * A booking opportunity for which an official quote has been issued.
         *
         * **Active.** Reached from [InitialRequest], or used directly as a lifecycle entry point
         * when the opportunity originated outside the adopting application, for example
         * by phone, email, in person, or in another system.
         *
         * Legal transitions:
         * - [toBooking]: `Quote → Booked`
         * - [cancel]: `Quote → Cancelled`
         *
         * Quote revisions are not lifecycle transitions. A model that has been revised any
         * number of times still inhabits the `Quote` phase. Revision history is
         * application-owned data.
         */
        interface Quote : Active {
            /**
             * Represents the legal lifecycle transition from a quoted opportunity to a
             * confirmed booking.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun toBooking(): Booked

            /**
             * Represents the legal lifecycle transition from a quoted opportunity to a
             * cancelled outcome, for example a declined, withdrawn, or expired quote.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun cancel(): Terminal.Cancelled
        }

        /**
         * A confirmed booking.
         *
         * **Active.** This protocol does not define what makes a booking confirmed. That is
         * business policy owned by the implementing application.
         *
         * Legal transitions:
         * - [complete]: `Booked → Completed`
         * - [cancel]: `Booked → Cancelled`
         *
         * Invoice revisions and change orders are not lifecycle transitions. If an application
         * associates financial documents with its booking models, a booking whose invoice has
         * changed any number of times still inhabits the `Booked` phase.
         */
        interface Booked : Active {
            /**
             * Represents the legal lifecycle transition from a confirmed booking to a
             * completed outcome, meaning the booked service or event was fulfilled.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun complete(): Terminal.Completed

            /**
             * Represents the legal lifecycle transition from a confirmed booking to a
             * cancelled outcome, meaning the booking ended without fulfillment.
             *
             * The implementing application determines the business conditions and concrete
             * data transformation necessary to perform the transition. Implementations may
             * declare a more specific return type.
             */
            fun cancel(): Terminal.Cancelled
        }
    }

    /**
     * Classification of the phases in which a booking lifecycle has reached its outcome.
     *
     * The terminal phases are exactly [Cancelled] and [Completed]. Terminal booking phases
     * are immutable historical outcomes of the booking lifecycle and expose no lifecycle
     * transitions.
     *
     * A terminal lifecycle does not mean that all business activity associated with the
     * booking has ended. Refunds, complaints, disputes, chargebacks, and credits are
     * orthogonal processes outside the booking lifecycle. They do not reopen or rewrite the
     * booking lifecycle.
     */
    sealed interface Terminal : BookingLifecycle {
        /**
         * A booking lifecycle that ended without fulfillment.
         *
         * **Terminal.** Reachable from every [Active] phase. Exposes no lifecycle transitions.
         *
         * Applications may represent cancellation with different concrete types depending on
         * where the lifecycle ended, for example a cancelled inquiry, a declined quote, or a
         * cancelled booking. The protocol only records the outcome. A later refund does not
         * change it.
         */
        interface Cancelled : Terminal

        /**
         * A booking whose booked service or event was fulfilled.
         *
         * **Terminal.** Reachable only from [Active.Booked]. Exposes no lifecycle transitions.
         *
         * Completion is an immutable booking lifecycle outcome. It stays true even if later
         * business processes, such as a complaint, a refund, or a dispute, reference the
         * booking.
         */
        interface Completed : Terminal
    }
}
