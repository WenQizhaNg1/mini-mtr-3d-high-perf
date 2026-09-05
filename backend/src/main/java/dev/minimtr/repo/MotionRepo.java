package dev.minimtr.repo;

import dev.minimtr.model.dto.MotionCheckpoint;
import dev.minimtr.model.entity.MotionNode;
import dev.minimtr.model.entity.PlannedRun;
import dev.minimtr.service.MotionPath;
import dev.minimtr.service.RoutePath;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MotionRepo {
    private static final String LOCK = "app:live-motion";
    private final DSLContext db;
    private final DataSource dataSource;
    private final JsonMapper mapper;

    public MotionRepo(DSLContext db, DataSource dataSource, JsonMapper mapper) {
        this.db = db; this.dataSource = dataSource; this.mapper = mapper;
    }

    public Connection acquireOwner() throws SQLException {
        var connection = dataSource.getConnection();
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, LOCK);
            try (var result = statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) throw new IllegalStateException("Another Java live service owns app.motion_checkpoint");
            }
            return connection;
        } catch (SQLException | RuntimeException error) {
            try { connection.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    public void releaseOwner(Connection owner) throws SQLException {
        try (owner; var statement = owner.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            statement.setString(1, LOCK); statement.execute();
        }
    }

    public Map<String, MotionPath> paths() {
        var paths = new LinkedHashMap<String, MotionPath>();
        db.fetch("""
                SELECT r.code, l.code AS line_id, l.colour, ST_AsBinary(g.geom) AS wkb,
                    (SELECT s.code FROM app.route_stop rs JOIN app.station s ON s.id = rs.station_id
                     WHERE rs.route_id = r.id ORDER BY rs.seq DESC LIMIT 1) AS destination
                FROM app.route r JOIN app.line l ON l.id = r.line_id
                JOIN app.operator o ON o.id = l.operator_id
                JOIN app.spatial_object g ON g.id = r.object_id WHERE o.code = 'mtr' ORDER BY r.code
                """).forEach(r -> {
                    String code = r.get("code", String.class);
                    paths.put(code, new MotionPath(code, r.get("line_id", String.class), r.get("colour", String.class),
                            Objects.requireNonNull(r.get("destination", String.class), "Missing route destination: " + code),
                            new RoutePath(r.get("wkb", byte[].class))));
                });
        if (paths.isEmpty()) throw new IllegalStateException("Import the MTR network before starting live motion");
        return paths;
    }

    public List<PlannedRun> runs(Map<String, MotionPath> paths, long now) {
        var patterns = new LinkedHashMap<String, String>();
        var nodes = new LinkedHashMap<String, List<MotionNode>>();
        db.fetch("""
                WITH active AS (
                    SELECT trip_id FROM app.trip_stop GROUP BY trip_id
                    HAVING min(departure_at) <= ?::timestamptz AND max(arrival_at) >= ?::timestamptz
                )
                SELECT t.source_key AS id, r.code AS pattern_id, s.arrival_at, s.departure_at,
                    station.code AS station, rs.fraction
                FROM active a JOIN app.trip t ON t.id = a.trip_id
                JOIN app.trip_stop s ON s.trip_id = t.id JOIN app.route r ON r.id = t.route_id
                JOIN app.line l ON l.id = r.line_id JOIN app.operator o ON o.id = l.operator_id
                JOIN app.route_stop rs ON rs.route_id = s.route_id AND rs.seq = s.seq
                JOIN app.station station ON station.id = rs.station_id
                WHERE o.code = 'mtr' ORDER BY t.source_key, s.seq
                """, Instant.ofEpochMilli(now + 1_800_000).atOffset(ZoneOffset.UTC),
                Instant.ofEpochMilli(now - 7_200_000).atOffset(ZoneOffset.UTC)).forEach(r -> {
                    String id = r.get("id", String.class), pattern = r.get("pattern_id", String.class);
                    patterns.put(id, pattern);
                    var run = nodes.computeIfAbsent(id, key -> new ArrayList<>());
                    double distance = paths.get(pattern).geometry().metricDistance(r.get("fraction", Double.class));
                    for (String column : List.of("arrival_at", "departure_at")) {
                        var time = r.get(column, OffsetDateTime.class);
                        if (time == null) continue;
                        var node = new MotionNode(time.toInstant().toEpochMilli(), distance, r.get("station", String.class));
                        if (run.isEmpty() || !run.getLast().equals(node)) run.add(node);
                    }
                });
        return nodes.entrySet().stream().map(e -> new PlannedRun(e.getKey(), patterns.get(e.getKey()), e.getValue())).toList();
    }

    public MotionCheckpoint checkpoint(Connection owner) throws SQLException {
        try (var statement = owner.prepareStatement("SELECT state::text FROM app.motion_checkpoint WHERE id = 1");
                var rows = statement.executeQuery()) {
            return rows.next() ? mapper.readValue(rows.getString(1), MotionCheckpoint.class) : null;
        }
    }

    public void save(Connection owner, MotionCheckpoint checkpoint) throws SQLException {
        // Use the lock-owning connection: losing the session also prevents any further checkpoint writes.
        try (var statement = owner.prepareStatement("""
                INSERT INTO app.motion_checkpoint (id, state) VALUES (1, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state
                """)) {
            statement.setString(1, mapper.writeValueAsString(checkpoint)); statement.executeUpdate();
        }
    }
}
