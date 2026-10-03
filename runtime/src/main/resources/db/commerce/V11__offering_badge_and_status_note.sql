-- Every stored offering now carries "badge", "statusNote", and "infoNote" (each null or nonblank text),
-- and stored catalogs decode strictly, so a catalog written before V11 can never be read.
-- No pre-V11 catalog is converted: recreate an ephemeral populated database rather than
-- pretend old revisions were written in the new representation. The table shape is unchanged.
LOCK TABLE commerce.offerings_snapshots IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM commerce.offerings_snapshots) THEN
        RAISE EXCEPTION 'V11 cannot add badge and status note to preexisting offerings catalogs; recreate the ephemeral database';
    END IF;
END
$$;
