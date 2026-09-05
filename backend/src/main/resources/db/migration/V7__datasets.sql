CREATE TABLE app.dataset (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9-]{0,63}$'),
    name text NOT NULL,
    ontology_code text NOT NULL,
    fields jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'object'),
    published boolean NOT NULL DEFAULT false
);

CREATE TABLE app.feature (
    dataset_id bigint NOT NULL REFERENCES app.dataset(id),
    object_id bigint NOT NULL REFERENCES app.spatial_object(id),
    properties jsonb NOT NULL CHECK (jsonb_typeof(properties) = 'object'),
    PRIMARY KEY (dataset_id, object_id)
);
CREATE INDEX feature_object_idx ON app.feature(object_id);

CREATE FUNCTION app.dataset_tile(z integer, x integer, y integer, query json)
RETURNS bytea LANGUAGE plpgsql STABLE PARALLEL SAFE AS $$
BEGIN
    IF z IS NULL OR x IS NULL OR y IS NULL OR z < 0 OR z > 22
        OR x < 0 OR y < 0 OR x >= (1::bigint << z) OR y >= (1::bigint << z) THEN
        RETURN ''::bytea;
    END IF;
    RETURN (
        SELECT coalesce(ST_AsMVT(tile, 'features', 4096, 'geom', 'feature_id'), ''::bytea)
        FROM (
            SELECT f.object_id AS feature_id, f.properties,
                ST_AsMVTGeom(ST_Transform(o.geom, 3857), ST_TileEnvelope(z,x,y), 4096,64,true) AS geom
            FROM app.dataset d
            JOIN app.feature f ON f.dataset_id = d.id
            JOIN app.spatial_object o ON o.id = f.object_id
            WHERE d.code = query->>'dataset' AND d.published
              AND o.geom && ST_Transform(ST_TileEnvelope(z,x,y, margin => 64.0/4096),4326)
        ) tile WHERE geom IS NOT NULL
    );
END;
$$;
COMMENT ON FUNCTION app.dataset_tile(integer,integer,integer,json) IS
'{"description":"Published WGS84 datasets; query parameter: dataset","minzoom":0,"maxzoom":22,"vector_layers":[{"id":"features","fields":{}}]}';
