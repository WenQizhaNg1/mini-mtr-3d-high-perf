-- Current application schema. Geometry is WGS84; business inputs are updated in place.
-- Adapter capabilities are validated against registered server implementations.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname = 'postgis') THEN
        RAISE EXCEPTION 'PostGIS is required; enable it in the target database before starting the backend';
    END IF;
END
$$;

-- Spatial identity, ontology, operators, networks, schedules and native styles.
CREATE SCHEMA app;

CREATE TABLE app.spatial_object (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    geom geometry(Geometry, 4326) NOT NULL,
    CHECK (NOT ST_IsEmpty(geom)),
    CHECK (ST_IsValid(geom)),
    CHECK (ST_NDims(geom) = 2)
);

CREATE INDEX spatial_object_geom_idx ON app.spatial_object USING gist (geom);

CREATE TABLE app.ontology (
    code text PRIMARY KEY,
    name text NOT NULL,
    geometry_types text[] NOT NULL,
    is_dynamic boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    description text NOT NULL DEFAULT '',
    CHECK (cardinality(geometry_types) > 0),
    CHECK (geometry_types <@ ARRAY[
        'Point', 'MultiPoint', 'LineString', 'MultiLineString',
        'Polygon', 'MultiPolygon'
    ]::text[])
);

CREATE TABLE app.operator (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    timezone text NOT NULL
);

CREATE TABLE app.line (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id bigint NOT NULL REFERENCES app.operator (id),
    code text NOT NULL,
    name text NOT NULL,
    name_en text,
    mode text NOT NULL CHECK (mode IN ('metro', 'rail', 'bus')),
    colour text NOT NULL CHECK (colour ~ '^#[0-9A-Fa-f]{6}$'),
    UNIQUE (operator_id, code)
);

CREATE TABLE app.station (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES app.spatial_object (id),
    operator_id bigint NOT NULL REFERENCES app.operator (id),
    code text NOT NULL,
    name text NOT NULL,
    name_en text,
    UNIQUE (operator_id, code)
);

CREATE TABLE app.stop (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES app.spatial_object (id),
    station_id bigint NOT NULL REFERENCES app.station (id),
    code text NOT NULL,
    name text,
    UNIQUE (station_id, code),
    UNIQUE (id, station_id)
);

CREATE TABLE app.route (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES app.spatial_object (id),
    line_id bigint NOT NULL REFERENCES app.line (id),
    code text NOT NULL,
    name text,
    direction text,
    reversed boolean NOT NULL DEFAULT false,
    UNIQUE (line_id, code)
);

CREATE INDEX route_object_idx ON app.route (object_id);

CREATE TABLE app.route_stop (
    route_id bigint NOT NULL REFERENCES app.route (id),
    seq integer NOT NULL CHECK (seq >= 0),
    station_id bigint NOT NULL REFERENCES app.station (id),
    stop_id bigint,
    distance_m double precision NOT NULL CHECK (distance_m >= 0),
    fraction double precision NOT NULL CHECK (fraction BETWEEN 0 AND 1),
    pickup boolean NOT NULL DEFAULT true,
    dropoff boolean NOT NULL DEFAULT true,
    PRIMARY KEY (route_id, seq),
    FOREIGN KEY (stop_id, station_id) REFERENCES app.stop (id, station_id)
);

CREATE INDEX route_stop_station_idx ON app.route_stop (station_id);

CREATE TABLE app.trip (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id bigint NOT NULL REFERENCES app.route (id),
    service_date date NOT NULL,
    code text,
    source text NOT NULL,
    source_key text NOT NULL,
    plan_type text NOT NULL CHECK (plan_type IN ('timetable', 'frequency', 'simulated')),
    UNIQUE (source, service_date, source_key),
    UNIQUE (id, route_id)
);

CREATE INDEX trip_service_date_idx ON app.trip (service_date);
CREATE INDEX trip_route_idx ON app.trip (route_id);

CREATE TABLE app.trip_stop (
    trip_id bigint NOT NULL,
    route_id bigint NOT NULL,
    seq integer NOT NULL,
    arrival_at timestamptz,
    departure_at timestamptz,
    PRIMARY KEY (trip_id, seq),
    FOREIGN KEY (trip_id, route_id) REFERENCES app.trip (id, route_id),
    FOREIGN KEY (route_id, seq) REFERENCES app.route_stop (route_id, seq),
    CHECK (arrival_at IS NULL OR departure_at IS NULL OR arrival_at <= departure_at)
);

CREATE TABLE app.style (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    basemap text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE app.layer (
    style_id bigint NOT NULL REFERENCES app.style (id),
    code text NOT NULL,
    source text NOT NULL,
    source_layer text,
    type text NOT NULL,
    position integer NOT NULL,
    minzoom double precision NOT NULL DEFAULT 0 CHECK (minzoom BETWEEN 0 AND 24),
    maxzoom double precision NOT NULL DEFAULT 24 CHECK (maxzoom BETWEEN 0 AND 24),
    layout jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(layout) = 'object'),
    paint jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(paint) = 'object'),
    filter jsonb,
    enabled boolean NOT NULL DEFAULT true,
    metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    PRIMARY KEY (style_id, code),
    UNIQUE (style_id, position),
    CHECK (minzoom < maxzoom)
);

-- Station membership may include a line without a current stopping pattern.
CREATE TABLE app.line_station (
    line_id bigint NOT NULL REFERENCES app.line (id),
    station_id bigint NOT NULL REFERENCES app.station (id),
    PRIMARY KEY (line_id, station_id)
);

CREATE INDEX line_station_station_idx ON app.line_station (station_id);

-- Source features retain their identity across file imports and drawing edits.
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
    source_key text NOT NULL CHECK (length(source_key) BETWEEN 1 AND 200),
    UNIQUE (dataset_id, source_key),
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
            SELECT f.object_id AS feature_id, f.source_key, f.properties,
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

-- Current data-source bindings and independent simulation/realtime parameters.
CREATE TABLE app.transit_config (
    operator_id bigint PRIMARY KEY REFERENCES app.operator(id),
    adapter text NOT NULL,
    mode text NOT NULL CHECK (mode IN ('simulation','realtime')),
    mapping jsonb NOT NULL CHECK (jsonb_typeof(mapping) = 'object'),
    simulation_params jsonb NOT NULL CHECK (jsonb_typeof(simulation_params) = 'object'),
    realtime_params jsonb NOT NULL CHECK (jsonb_typeof(realtime_params) = 'object')
);

-- Operator-scoped traffic tiles; these contain no moving vehicles.
CREATE VIEW app.map_routes AS
SELECT r.id AS feature_id,r.code AS id,l.code AS line_id,l.colour,op.code AS operator,
       first_stop.code AS from_code,last_stop.code AS to_code,
       ST_Length(o.geom::geography) AS length_m,
       (CASE WHEN r.reversed THEN ST_Reverse(o.geom) ELSE o.geom END)::geometry(LineString,4326) AS geom
FROM app.route r JOIN app.line l ON l.id=r.line_id JOIN app.operator op ON op.id=l.operator_id
JOIN app.spatial_object o ON o.id=r.object_id
LEFT JOIN LATERAL (SELECT s.code FROM app.route_stop rs JOIN app.station s ON s.id=rs.station_id
    WHERE rs.route_id=r.id ORDER BY rs.seq LIMIT 1) first_stop ON true
LEFT JOIN LATERAL (SELECT s.code FROM app.route_stop rs JOIN app.station s ON s.id=rs.station_id
    WHERE rs.route_id=r.id ORDER BY rs.seq DESC LIMIT 1) last_stop ON true;

CREATE VIEW app.map_stations AS
SELECT s.id AS feature_id,s.code,s.name,s.name AS name_zh,s.name_en,op.code AS operator,
       coalesce(m.line_ids,'') AS line_ids,coalesce(m.n,0)>1 AS interchange,
       ST_PointOnSurface(o.geom)::geometry(Point,4326) AS geom
FROM app.station s JOIN app.operator op ON op.id=s.operator_id JOIN app.spatial_object o ON o.id=s.object_id
LEFT JOIN LATERAL (SELECT string_agg(l.code,',' ORDER BY l.code) AS line_ids,count(*) AS n
    FROM app.line_station ls JOIN app.line l ON l.id=ls.line_id WHERE ls.station_id=s.id) m ON true;

CREATE FUNCTION app.transit_tile(z integer,x integer,y integer,query json)
RETURNS bytea LANGUAGE plpgsql STABLE PARALLEL SAFE AS $$
DECLARE bounds geometry; routes bytea; stations bytea;
BEGIN
    IF z IS NULL OR x IS NULL OR y IS NULL OR z<0 OR z>22
        OR x<0 OR y<0 OR x>=(1::bigint<<z) OR y>=(1::bigint<<z) THEN RETURN ''::bytea; END IF;
    bounds=ST_TileEnvelope(z,x,y);
    SELECT coalesce(ST_AsMVT(t,'routes',4096,'geom','feature_id'),''::bytea) INTO routes FROM (
        SELECT feature_id,id,line_id,colour,from_code,to_code,length_m,
            ST_AsMVTGeom(ST_Transform(geom,3857),bounds,4096,64,true) AS geom
        FROM app.map_routes WHERE operator=query->>'operator'
            AND geom && ST_Transform(ST_TileEnvelope(z,x,y,margin=>64.0/4096),4326)
    ) t WHERE geom IS NOT NULL;
    SELECT coalesce(ST_AsMVT(t,'stations',4096,'geom','feature_id'),''::bytea) INTO stations FROM (
        SELECT feature_id,code,name,name_zh,name_en,line_ids,interchange,
            ST_AsMVTGeom(ST_Transform(geom,3857),bounds,4096,64,true) AS geom
        FROM app.map_stations WHERE operator=query->>'operator'
            AND geom && ST_Transform(ST_TileEnvelope(z,x,y,margin=>64.0/4096),4326)
    ) t WHERE geom IS NOT NULL;
    RETURN routes||stations;
END;
$$;
COMMENT ON FUNCTION app.transit_tile(integer,integer,integer,json) IS
'{"description":"Transit network; required query parameter: operator","minzoom":0,"maxzoom":22,"vector_layers":[{"id":"routes","fields":{}},{"id":"stations","fields":{}}]}';

-- Initial ontology and native light/dark styles. No operator or timetable data is seeded.
INSERT INTO app.ontology (code, name, geometry_types, description) VALUES
    ('station', '车站', ARRAY['Point', 'MultiPoint', 'Polygon', 'MultiPolygon'], '站级设施，几何存储于 spatial_object'),
    ('stop', '停靠点', ARRAY['Point', 'MultiPoint', 'Polygon', 'MultiPolygon'], '已知的具体停靠设施，不根据站级数据伪造站台'),
    ('route', '运行路径', ARRAY['LineString'], '有方向的运行方案路径，不代表物理轨道拓扑');
INSERT INTO app.style (id, code, name, basemap, metadata) OVERRIDING SYSTEM VALUE VALUES (1, 'mtr-light', '港铁 · 日间', 'positron', '{"group": "transit"}');
INSERT INTO app.style (id, code, name, basemap, metadata) OVERRIDING SYSTEM VALUE VALUES (2, 'mtr-dark', '港铁 · 夜间', 'osm-liberty-dark', '{"group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (1, 'mtr-stations', 'transit', 'stations', 'circle', 3, 0, 24, '{}', '{"circle-color": "#ffffff", "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, ["case", ["get", "interchange"], 3, 2], 11, ["case", ["get", "interchange"], 5, 3.5], 14, ["case", ["get", "interchange"], 17, 13], 18, ["case", ["get", "interchange"], 42, 32], 22, ["case", ["get", "interchange"], 120, 96]], "circle-pitch-scale": "map", "circle-stroke-color": "#111827", "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1.5, 14, 3, 18, 5, 22, 8], "circle-pitch-alignment": "map"}', NULL, true, '{"role": "stations", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (1, 'mtr-trains', 'vehicles', NULL, 'fill-extrusion', 4, 0, 24, '{}', '{"fill-extrusion-color": ["case", ["boolean", ["feature-state", "selected"], false], "#facc15", ["interpolate", ["linear"], 0.22, 0, ["get", "colour"], 1, "#ffffff"]], "fill-extrusion-height": ["get", "height"], "fill-extrusion-opacity": 1, "fill-extrusion-vertical-gradient": true}', NULL, true, '{"role": "vehicles", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (1, 'mtr-route-casing', 'transit', 'routes', 'line', 1, 0, 24, '{"line-cap": "round", "line-join": "round"}', '{"line-color": "#ffffff", "line-width": ["interpolate", ["exponential", 1.4142135623730951], ["zoom"], 0, 0.08984375, 11, 4.065863991822647, 14, 16.099999999999998, 16, 6.9]}', NULL, true, '{"role": "routes", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (2, 'mtr-trains', 'vehicles', NULL, 'fill-extrusion', 4, 0, 24, '{}', '{"fill-extrusion-color": ["case", ["boolean", ["feature-state", "selected"], false], "#facc15", ["interpolate", ["linear"], 0.22, 0, ["get", "colour"], 1, "#ffffff"]], "fill-extrusion-height": ["get", "height"], "fill-extrusion-opacity": 1, "fill-extrusion-vertical-gradient": true}', NULL, true, '{"role": "vehicles", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (1, 'mtr-station-labels', 'transit', 'stations', 'symbol', 5, 10.5, 24, '{"text-font": ["Noto Sans CJK SC Bold"], "text-size": ["interpolate", ["linear"], ["zoom"], 10.5, 12, 14, 16, 18, 18], "text-field": ["case", ["==", ["global-state", "language"], "en"], ["get", "name_en"], ["get", "name_zh"]], "text-anchor": "top", "text-offset": ["interpolate", ["linear"], ["zoom"], 10.5, ["literal", [0, 1.1]], 14, ["literal", [0, 1.6]], 18, ["literal", [0, 3]], 22, ["literal", [0, 7.5]]], "text-padding": 3, "text-optional": true}', '{"text-color": "#343b43", "text-halo-blur": 0, "text-halo-color": "#ffffff", "text-halo-width": 1.5}', NULL, true, '{"role": "stations", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (2, 'mtr-stations', 'transit', 'stations', 'circle', 3, 0, 24, '{}', '{"circle-color": "#ffffff", "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, ["case", ["get", "interchange"], 3, 2], 11, ["case", ["get", "interchange"], 5, 3.5], 14, ["case", ["get", "interchange"], 17, 13], 18, ["case", ["get", "interchange"], 42, 32], 22, ["case", ["get", "interchange"], 120, 96]], "circle-pitch-scale": "map", "circle-stroke-color": "#111827", "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1.5, 14, 3, 18, 5, 22, 8], "circle-pitch-alignment": "map"}', NULL, true, '{"role": "stations", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (2, 'mtr-station-labels', 'transit', 'stations', 'symbol', 5, 10.5, 24, '{"text-font": ["Noto Sans CJK SC Bold"], "text-size": ["interpolate", ["linear"], ["zoom"], 10.5, 12, 14, 16, 18, 18], "text-field": ["case", ["==", ["global-state", "language"], "en"], ["get", "name_en"], ["get", "name_zh"]], "text-anchor": "top", "text-offset": ["interpolate", ["linear"], ["zoom"], 10.5, ["literal", [0, 1.1]], 14, ["literal", [0, 1.6]], 18, ["literal", [0, 3]], 22, ["literal", [0, 7.5]]], "text-padding": 3, "text-optional": true}', '{"text-color": "#f1f5f9", "text-halo-blur": 0, "text-halo-color": "#18222f", "text-halo-width": 1.5}', NULL, true, '{"role": "stations", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (1, 'mtr-routes', 'transit', 'routes', 'line', 2, 0, 24, '{"line-cap": "round", "line-join": "round"}', '{"line-color": ["coalesce", ["get", "colour"], "#64748b"], "line-width": ["interpolate", ["exponential", 1.4142135623730951], ["zoom"], 0, 0.078125, 11, 3.5355339059327373, 14, 14, 16, 6]}', NULL, true, '{"role": "routes", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (2, 'mtr-route-casing', 'transit', 'routes', 'line', 1, 0, 24, '{"line-cap": "round", "line-join": "round"}', '{"line-color": "#283341", "line-width": ["interpolate", ["exponential", 1.4142135623730951], ["zoom"], 0, 0.08984375, 11, 4.065863991822647, 14, 16.099999999999998, 16, 6.9]}', NULL, true, '{"role": "routes", "group": "transit"}');
INSERT INTO app.layer (style_id, code, source, source_layer, type, "position", minzoom, maxzoom, layout, paint, filter, enabled, metadata) VALUES (2, 'mtr-routes', 'transit', 'routes', 'line', 2, 0, 24, '{"line-cap": "round", "line-join": "round"}', '{"line-color": ["coalesce", ["get", "colour"], "#64748b"], "line-width": ["interpolate", ["exponential", 1.4142135623730951], ["zoom"], 0, 0.078125, 11, 3.5355339059327373, 14, 14, 16, 6]}', NULL, true, '{"role": "routes", "group": "transit"}');
SELECT setval(pg_get_serial_sequence('app.style','id'),(SELECT max(id) FROM app.style));
