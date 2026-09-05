-- Stable tile contract, backed exclusively by the new business tables.
CREATE VIEW app.map_routes AS
SELECT r.id AS feature_id, r.code AS id, l.code AS line_id,
       first_stop.code AS from_code, last_stop.code AS to_code,
       l.colour, ST_Length(o.geom::geography) AS length_m,
       o.geom::geometry(LineString, 4326) AS geom
FROM app.route r
JOIN app.spatial_object o ON o.id = r.object_id
JOIN app.line l ON l.id = r.line_id
JOIN app.operator op ON op.id = l.operator_id AND op.code = 'mtr'
LEFT JOIN LATERAL (
    SELECT s.code FROM app.route_stop rs JOIN app.station s ON s.id = rs.station_id
    WHERE rs.route_id = r.id ORDER BY rs.seq LIMIT 1
) first_stop ON true
LEFT JOIN LATERAL (
    SELECT s.code FROM app.route_stop rs JOIN app.station s ON s.id = rs.station_id
    WHERE rs.route_id = r.id ORDER BY rs.seq DESC LIMIT 1
) last_stop ON true;

CREATE VIEW app.map_stations AS
SELECT s.id AS feature_id, s.code, s.name, s.name AS name_zh, s.name_en,
       coalesce(membership.line_ids, '') AS line_ids,
       coalesce(membership.line_count, 0) > 1 AS interchange,
       ST_PointOnSurface(o.geom)::geometry(Point, 4326) AS geom
FROM app.station s
JOIN app.spatial_object o ON o.id = s.id
JOIN app.operator op ON op.id = s.operator_id AND op.code = 'mtr'
LEFT JOIN LATERAL (
    SELECT string_agg(l.code, ',' ORDER BY l.code) AS line_ids, count(*) AS line_count
    FROM app.line_station ls JOIN app.line l ON l.id = ls.line_id
    WHERE ls.station_id = s.id
) membership ON true;
