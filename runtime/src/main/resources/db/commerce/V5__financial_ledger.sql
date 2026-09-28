CREATE TABLE commerce.financial_document_snapshots (
    document_id uuid NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    previous_version integer,
    stage text NOT NULL CHECK (stage IN ('ESTIMATE', 'QUOTE', 'INVOICE')),
    PRIMARY KEY (document_id, version),
    UNIQUE (document_id, previous_version),
    CONSTRAINT financial_document_sequence CHECK (
        (version = 1 AND previous_version IS NULL) OR
        (version > 1 AND previous_version = version - 1)
    ),
    CONSTRAINT financial_document_predecessor FOREIGN KEY (document_id, previous_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE TABLE commerce.financial_document_lines (
    document_id uuid NOT NULL,
    version integer NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    line_id uuid NOT NULL,
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    sub_description text,
    quantity numeric,
    price_amount numeric NOT NULL,
    price_currency char(3) NOT NULL,
    tax_amount numeric NOT NULL,
    PRIMARY KEY (document_id, version, line_id),
    UNIQUE (document_id, version, position),
    FOREIGN KEY (document_id, version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE TABLE commerce.payment_records (
    payment_id uuid PRIMARY KEY,
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    method text NOT NULL CHECK (method IN ('CASH', 'CHECK', 'CARD', 'BANK_TRANSFER', 'DIGITAL_WALLET', 'OTHER')),
    received_at_seconds bigint NOT NULL,
    received_at_nanos integer NOT NULL CHECK (received_at_nanos BETWEEN 0 AND 999999999),
    external_provider text,
    external_reference text,
    CONSTRAINT payment_external_pair CHECK (
        (external_provider IS NULL AND external_reference IS NULL) OR
        (external_provider IS NOT NULL AND length(btrim(external_provider)) > 0 AND
         external_reference IS NOT NULL AND length(btrim(external_reference)) > 0)
    ),
    UNIQUE (external_provider, external_reference)
);

CREATE TABLE commerce.payment_allocations (
    allocation_id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES commerce.payment_records (payment_id),
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    allocated_at_seconds bigint NOT NULL,
    allocated_at_nanos integer NOT NULL CHECK (allocated_at_nanos BETWEEN 0 AND 999999999),
    FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE INDEX payment_allocations_payment ON commerce.payment_allocations (payment_id);
CREATE INDEX payment_allocations_lineage ON commerce.payment_allocations (document_id);
