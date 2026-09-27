CREATE TABLE commerce.offerings_snapshots (
    catalog_id uuid NOT NULL,
    revision integer NOT NULL CHECK (revision >= 1),
    previous_revision integer,
    PRIMARY KEY (catalog_id, revision),
    CONSTRAINT offerings_revision_sequence CHECK (
        (revision = 1 AND previous_revision IS NULL) OR
        (revision > 1 AND previous_revision = revision - 1)
    ),
    CONSTRAINT offerings_previous_revision FOREIGN KEY (catalog_id, previous_revision)
        REFERENCES commerce.offerings_snapshots (catalog_id, revision)
);

CREATE TABLE commerce.offering_categories (
    catalog_id uuid NOT NULL,
    revision integer NOT NULL,
    category_key text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    description text,
    minimum_selections integer NOT NULL CHECK (minimum_selections >= 0),
    maximum_selections integer,
    PRIMARY KEY (catalog_id, revision, category_key),
    UNIQUE (catalog_id, revision, position),
    FOREIGN KEY (catalog_id, revision) REFERENCES commerce.offerings_snapshots (catalog_id, revision),
    CHECK (length(btrim(category_key)) > 0),
    CHECK (maximum_selections IS NULL OR
        (maximum_selections > 0 AND maximum_selections >= minimum_selections))
);

CREATE TABLE commerce.offerings (
    catalog_id uuid NOT NULL,
    revision integer NOT NULL,
    offering_key text NOT NULL,
    category_key text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    description text,
    price_kind text,
    price_amount numeric,
    price_currency char(3),
    quantity_dimension text,
    duration_seconds bigint,
    duration_nanos integer,
    PRIMARY KEY (catalog_id, revision, offering_key),
    UNIQUE (catalog_id, revision, position),
    FOREIGN KEY (catalog_id, revision, category_key)
        REFERENCES commerce.offering_categories (catalog_id, revision, category_key),
    CHECK (length(btrim(offering_key)) > 0),
    CHECK (
        (price_kind IS NULL AND price_amount IS NULL AND price_currency IS NULL AND
            quantity_dimension IS NULL AND duration_seconds IS NULL AND duration_nanos IS NULL) OR
        (price_kind IS NOT NULL AND price_kind = 'FIXED' AND price_amount IS NOT NULL AND price_currency IS NOT NULL AND
            quantity_dimension IS NULL AND duration_seconds IS NULL AND duration_nanos IS NULL) OR
        (price_kind IS NOT NULL AND price_kind = 'PER_QUANTITY' AND price_amount IS NOT NULL AND price_currency IS NOT NULL AND
            quantity_dimension IS NOT NULL AND length(btrim(quantity_dimension)) > 0 AND
            duration_seconds IS NULL AND duration_nanos IS NULL) OR
        (price_kind IS NOT NULL AND price_kind = 'PER_DURATION' AND price_amount IS NOT NULL AND price_currency IS NOT NULL AND
            quantity_dimension IS NULL AND duration_seconds IS NOT NULL AND duration_nanos IS NOT NULL AND
            duration_seconds >= 0 AND duration_nanos BETWEEN 0 AND 999999999 AND
            (duration_seconds > 0 OR duration_nanos > 0))
    )
);
