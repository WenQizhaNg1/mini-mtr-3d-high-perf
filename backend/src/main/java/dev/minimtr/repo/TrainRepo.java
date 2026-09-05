package dev.minimtr.repo;

import dev.minimtr.model.entity.TrainWindow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class TrainRepo {
    private final DSLContext db;

    public TrainRepo(DSLContext db) {
        this.db = db;
    }

    public List<TrainWindow> windowsAt(Instant at) {
        var timestamp = at.atOffset(ZoneOffset.UTC);
        return db.fetch("""
                WITH active_trips AS (
                    SELECT trip_id FROM app.trip_stop
                    GROUP BY trip_id HAVING min(departure_at) <= ?::timestamptz AND max(arrival_at) > ?::timestamptz
                ), stops AS (
                    SELECT t.source_key AS id, l.code AS line_id, r.code AS pattern_id, l.colour, r.object_id,
                        station.code AS station, rs.fraction, s.arrival_at, s.departure_at,
                        lead(station.code) OVER w AS next_station,
                        lead(rs.fraction) OVER w AS next_fraction,
                        lead(s.arrival_at) OVER w AS next_arrival_at,
                        last_value(station.code) OVER (
                            PARTITION BY t.id ORDER BY s.seq
                            ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
                        ) AS destination_station
                    FROM active_trips a JOIN app.trip t ON t.id = a.trip_id
                    JOIN app.trip_stop s ON s.trip_id = t.id
                    JOIN app.route r ON r.id = t.route_id
                    JOIN app.line l ON l.id = r.line_id
                    JOIN app.operator o ON o.id = l.operator_id
                    JOIN app.route_stop rs ON rs.route_id = s.route_id AND rs.seq = s.seq
                    JOIN app.station station ON station.id = rs.station_id
                    WHERE o.code = 'mtr'
                    WINDOW w AS (PARTITION BY t.id ORDER BY s.seq)
                )
                SELECT * FROM stops WHERE next_arrival_at > ?::timestamptz AND departure_at IS NOT NULL
                    AND (departure_at <= ?::timestamptz OR arrival_at <= ?::timestamptz)
                ORDER BY id
                """, timestamp, timestamp, timestamp, timestamp, timestamp).map(r -> new TrainWindow(
                        r.get("id", String.class), r.get("line_id", String.class), r.get("pattern_id", String.class),
                        r.get("colour", String.class), r.get("object_id", Long.class), r.get("station", String.class),
                        r.get("next_station", String.class), r.get("destination_station", String.class),
                        r.get("fraction", Double.class), r.get("next_fraction", Double.class),
                        instant(r.get("arrival_at", OffsetDateTime.class)),
                        instant(r.get("departure_at", OffsetDateTime.class)),
                        instant(r.get("next_arrival_at", OffsetDateTime.class))));
    }

    public byte[] path(long objectId) {
        return db.fetchSingle("SELECT ST_AsBinary(geom) FROM app.spatial_object WHERE id = ?", objectId)
                .get(0, byte[].class);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
