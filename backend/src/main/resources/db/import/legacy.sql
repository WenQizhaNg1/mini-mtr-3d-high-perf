-- Explicit one-time snapshot import. Executed within a repeatable-read transaction.
-- Temporary identity maps keep source identity out of spatial_object.
CREATE TEMP TABLE station_map ON COMMIT DROP AS
SELECT code, nextval(pg_get_serial_sequence('app.spatial_object', 'id')) AS id
FROM mtr.stations ORDER BY code;

INSERT INTO app.spatial_object (id, geom) OVERRIDING SYSTEM VALUE
SELECT x.id, s.geom FROM mtr.stations s JOIN station_map x USING (code);

INSERT INTO app.station (id, operator_id, code, name, name_en)
SELECT x.id, o.id, s.code, s.name_zh, s.name_en
FROM mtr.stations s JOIN station_map x USING (code)
CROSS JOIN app.operator o WHERE o.code = 'mtr';

CREATE TEMP TABLE route_map ON COMMIT DROP AS
SELECT id AS code, nextval(pg_get_serial_sequence('app.spatial_object', 'id')) AS object_id
FROM mtr.route_patterns ORDER BY id;

INSERT INTO app.spatial_object (id, geom) OVERRIDING SYSTEM VALUE
SELECT x.object_id, r.geom FROM mtr.route_patterns r JOIN route_map x ON x.code = r.id;

INSERT INTO app.route (object_id, line_id, code, name)
SELECT x.object_id, l.id, r.id, r.from_name || ' → ' || r.to_name
FROM mtr.route_patterns r JOIN route_map x ON x.code = r.id
JOIN app.line l ON l.code = r.line_id
JOIN app.operator o ON o.id = l.operator_id AND o.code = 'mtr';

INSERT INTO app.route_stop (route_id, seq, station_id, distance_m, fraction)
SELECT r.id, s.stop_sequence, x.id, s.distance_m, s.fraction
FROM mtr.route_stops s JOIN app.route r ON r.code = s.pattern_id
JOIN station_map x ON x.code = s.station_code;

-- Legacy plans are synthetic headway/speed estimates, not official timetables.
INSERT INTO app.trip (route_id, service_date, code, source, source_key, plan_type)
SELECT r.id, t.service_date, t.id, 'legacy-mtr', t.id, 'simulated'
FROM mtr.train_runs t JOIN app.route r ON r.code = t.pattern_id;

-- Preserve unknown terminal arrival/departure as NULL; never invent dwell times.
INSERT INTO app.trip_stop (trip_id, route_id, seq, arrival_at, departure_at)
SELECT t.id, t.route_id, times.seq, max(times.arrival_at), max(times.departure_at)
FROM (
    SELECT train_id, from_stop_sequence AS seq, NULL::timestamptz AS arrival_at, departure_at
    FROM mtr.train_legs
    UNION ALL
    SELECT train_id, to_stop_sequence AS seq, arrival_at, NULL::timestamptz AS departure_at
    FROM mtr.train_legs
) times JOIN app.trip t ON t.source = 'legacy-mtr' AND t.source_key = times.train_id
GROUP BY t.id, t.route_id, times.seq;
