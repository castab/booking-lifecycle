-- A financial document line and an offerings category or offering have no identity or
-- lifecycle outside the immutable snapshot that contains them. Store them in that snapshot's
-- row instead of normalizing them into child tables.
--
-- No pre-V9 snapshots are converted: recreate an ephemeral populated database rather than
-- pretend the old child rows were ever written in the new representation. Released
-- migrations remain unchanged.
-- Hold both parents against concurrent inserts until the check and DDL commit.
LOCK TABLE commerce.financial_document_snapshots, commerce.offerings_snapshots IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM commerce.financial_document_snapshots) THEN
        RAISE EXCEPTION 'V9 cannot move preexisting financial document lines into their snapshots; recreate the ephemeral database';
    END IF;
    IF EXISTS (SELECT 1 FROM commerce.offerings_snapshots) THEN
        RAISE EXCEPTION 'V9 cannot move preexisting offerings catalog contents into their snapshots; recreate the ephemeral database';
    END IF;
END
$$;

DROP TABLE commerce.financial_document_lines;
DROP TABLE commerce.offerings;
DROP TABLE commerce.offering_categories;

-- The ordered line items of the snapshot, a JSON array. The runtime owns the element shape.
ALTER TABLE commerce.financial_document_snapshots
    ADD COLUMN lines jsonb NOT NULL,
    ADD CONSTRAINT financial_document_lines_array CHECK (jsonb_typeof(lines) = 'array');

-- The ordered categories and offerings of the revision, a JSON object with two arrays.
-- The runtime owns the element shapes.
ALTER TABLE commerce.offerings_snapshots
    ADD COLUMN catalog jsonb NOT NULL,
    ADD CONSTRAINT offerings_catalog_shape CHECK (
        jsonb_typeof(catalog) = 'object' AND
        coalesce(jsonb_typeof(catalog -> 'categories'), '') = 'array' AND
        coalesce(jsonb_typeof(catalog -> 'offerings'), '') = 'array'
    );
