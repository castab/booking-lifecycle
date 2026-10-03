-- An offerings catalog keeps only its current revision. The revision number still advances on
-- every change, as a concurrency and staleness token, but earlier revisions are not retained.
-- The row also holds the last representation of every retired key and the revision it was
-- last present in, so used keys stay reserved and can be restored.
--
-- No catalog history is converted: recreate an ephemeral populated database. Released
-- migrations remain unchanged.
LOCK TABLE commerce.offerings_snapshots IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM commerce.offerings_snapshots) THEN
        RAISE EXCEPTION 'V12 cannot convert preexisting offerings catalog revisions; recreate the ephemeral database';
    END IF;
END
$$;

DROP TABLE commerce.offerings_snapshots;

-- The current categories and offerings, in order, plus the retired entries. The runtime owns
-- the element shapes.
CREATE TABLE commerce.offerings_catalogs (
    catalog_id uuid PRIMARY KEY,
    revision integer NOT NULL CONSTRAINT offerings_catalog_revision CHECK (revision >= 1),
    catalog jsonb NOT NULL CONSTRAINT offerings_catalog_contents CHECK (
        jsonb_typeof(catalog) = 'object' AND
        coalesce(jsonb_typeof(catalog -> 'categories'), '') = 'array' AND
        coalesce(jsonb_typeof(catalog -> 'offerings'), '') = 'array' AND
        coalesce(jsonb_typeof(catalog -> 'retiredCategories'), '') = 'array' AND
        coalesce(jsonb_typeof(catalog -> 'retiredOfferings'), '') = 'array'
    )
);
