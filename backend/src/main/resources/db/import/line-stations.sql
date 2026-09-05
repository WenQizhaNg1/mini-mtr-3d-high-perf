INSERT INTO app.line_station (line_id, station_id)
SELECT l.id, s.id
FROM mtr.stations old
CROSS JOIN LATERAL unnest(string_to_array(old.line_ids, ',')) AS membership(code)
JOIN app.operator o ON o.code = 'mtr'
JOIN app.line l ON l.operator_id = o.id AND l.code = membership.code
JOIN app.station s ON s.operator_id = o.id AND s.code = old.code;
