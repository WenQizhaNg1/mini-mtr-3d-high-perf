-- Bootstrap only: business tables are still under review.
-- Existing Drizzle-managed schemas and tables are not adopted or modified.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'postgis') THEN
        RAISE EXCEPTION 'PostGIS is required; enable it in the target database before starting the backend';
    END IF;
END
$$;
