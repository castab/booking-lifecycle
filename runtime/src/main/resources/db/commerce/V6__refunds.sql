CREATE TABLE commerce.refund_records (
    refund_id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES commerce.payment_records (payment_id),
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    method text NOT NULL CHECK (method IN ('CASH', 'CHECK', 'CARD', 'BANK_TRANSFER', 'DIGITAL_WALLET', 'OTHER')),
    refunded_at_seconds bigint NOT NULL,
    refunded_at_nanos integer NOT NULL CHECK (refunded_at_nanos BETWEEN 0 AND 999999999),
    external_provider text,
    external_reference text,
    CONSTRAINT refund_external_pair CHECK (
        (external_provider IS NULL AND external_reference IS NULL) OR
        (external_provider IS NOT NULL AND length(btrim(external_provider)) > 0 AND
         external_reference IS NOT NULL AND length(btrim(external_reference)) > 0)
    ),
    UNIQUE (external_provider, external_reference)
);

CREATE TABLE commerce.refund_allocations (
    refund_allocation_id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES commerce.refund_records (refund_id),
    payment_allocation_id uuid NOT NULL REFERENCES commerce.payment_allocations (allocation_id),
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    allocated_at_seconds bigint NOT NULL,
    allocated_at_nanos integer NOT NULL CHECK (allocated_at_nanos BETWEEN 0 AND 999999999)
);

CREATE INDEX refund_records_payment ON commerce.refund_records (payment_id);
CREATE INDEX refund_allocations_refund ON commerce.refund_allocations (refund_id);
CREATE INDEX refund_allocations_payment_allocation ON commerce.refund_allocations (payment_allocation_id);
