package dev.minimtr.repo;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.ImportResult;
import org.jooq.DSLContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Repository;

@Repository
public class LegacyImportRepo {
    private final DSLContext db;

    public LegacyImportRepo(DSLContext db) {
        this.db = db;
    }

    public void requireEmptyTarget() {
        db.execute("LOCK TABLE app.operator IN EXCLUSIVE MODE");
        if (db.fetchOne("SELECT EXISTS (SELECT 1 FROM app.operator) OR EXISTS (SELECT 1 FROM app.spatial_object)")
                .get(0, Boolean.class)) {
            throw new IllegalStateException("Legacy import requires an empty app business schema; existing data will not be overwritten");
        }
    }

    public void insertOperatorAndLines(ServiceSettings settings) {
        long operatorId = db.fetchOne("""
                INSERT INTO app.operator (code, name, timezone)
                VALUES ('mtr', '港鐵', 'Asia/Hong_Kong') RETURNING id
                """).get(0, Long.class);
        for (var line : settings.lines()) {
            db.execute("""
                    INSERT INTO app.line (operator_id, code, name, name_en, mode, colour)
                    VALUES (?, ?, ?, ?, 'metro', ?)
                    """, operatorId, line.lineId(), line.nameZh(), line.nameEn(), line.colour());
        }
    }

    public void copyLegacy() {
        db.connection(connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/import/legacy.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/import/line-stations.sql"));
        });
    }

    public void setDirection(String routeCode, String direction) {
        db.execute("UPDATE app.route SET direction = ? WHERE code = ?", direction, routeCode);
    }

    public ImportResult validateAndCount() {
        // Fail the transaction instead of silently dropping unmatched source rows.
        boolean valid = db.fetchOne("""
                SELECT
                    (SELECT count(*) FROM app.station) = (SELECT count(*) FROM mtr.stations)
                    AND (SELECT count(*) FROM app.route) = (SELECT count(*) FROM mtr.route_patterns)
                    AND (SELECT count(*) FROM app.route_stop) = (SELECT count(*) FROM mtr.route_stops)
                    AND (SELECT count(*) FROM app.trip) = (SELECT count(*) FROM mtr.train_runs)
                    AND (SELECT count(*) FROM app.line_station) = (
                        SELECT sum(cardinality(string_to_array(line_ids, ','))) FROM mtr.stations)
                    AND NOT EXISTS (
                        SELECT 1 FROM mtr.train_legs old
                        LEFT JOIN app.trip t ON t.source = 'legacy-mtr' AND t.source_key = old.train_id
                        LEFT JOIN app.trip_stop a ON a.trip_id = t.id AND a.seq = old.from_stop_sequence
                        LEFT JOIN app.trip_stop b ON b.trip_id = t.id AND b.seq = old.to_stop_sequence
                        WHERE a.departure_at IS DISTINCT FROM old.departure_at
                            OR b.arrival_at IS DISTINCT FROM old.arrival_at)
                    AND NOT EXISTS (
                        SELECT 1 FROM app.trip t JOIN mtr.train_runs old ON old.id = t.source_key
                        LEFT JOIN app.trip_stop s ON s.trip_id = t.id
                        GROUP BY t.id, old.starts_at, old.ends_at
                        HAVING min(s.departure_at) IS DISTINCT FROM old.starts_at
                            OR max(s.arrival_at) IS DISTINCT FROM old.ends_at)
                    AND NOT EXISTS (
                        SELECT 1 FROM app.trip_stop a JOIN app.trip_stop b
                            ON b.trip_id = a.trip_id AND b.seq = a.seq + 1
                        WHERE a.departure_at > b.arrival_at)
                    AND NOT EXISTS (
                        SELECT 1 FROM app.route_stop a JOIN app.route_stop b
                            ON b.route_id = a.route_id AND b.seq = a.seq + 1
                        WHERE a.distance_m >= b.distance_m OR a.fraction > b.fraction)
                    AND NOT EXISTS (
                        SELECT 1 FROM app.station s JOIN app.spatial_object o ON o.id = s.id
                        WHERE NOT EXISTS (SELECT 1 FROM app.ontology t WHERE t.code = 'station'
                            AND t.enabled AND GeometryType(o.geom) = ANY (
                                SELECT upper(unnest(t.geometry_types)))))
                    AND NOT EXISTS (
                        SELECT 1 FROM app.route r JOIN app.spatial_object o ON o.id = r.object_id
                        WHERE NOT EXISTS (SELECT 1 FROM app.ontology t WHERE t.code = 'route'
                            AND t.enabled AND GeometryType(o.geom) = ANY (
                                SELECT upper(unnest(t.geometry_types)))))
                """).get(0, Boolean.class);
        if (!valid) throw new IllegalStateException("Legacy import validation failed: counts, geometry, distances or timetable mismatch");
        return db.fetchOne("""
                SELECT (SELECT count(*)::int FROM app.station),
                    (SELECT count(*)::int FROM app.route),
                    (SELECT count(*)::int FROM app.route_stop),
                    (SELECT count(*)::int FROM app.trip),
                    (SELECT count(*)::int FROM app.trip_stop)
                """).map(r -> new ImportResult(r.get(0, Integer.class), r.get(1, Integer.class),
                        r.get(2, Integer.class), r.get(3, Integer.class), r.get(4, Integer.class)));
    }
}
