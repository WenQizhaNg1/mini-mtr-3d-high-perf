package dev.minimtr.repo;

import dev.minimtr.model.dto.PlannedTrip;
import dev.minimtr.model.entity.RouteStop;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepo {
    private final DSLContext db;

    public ScheduleRepo(DSLContext db) {
        this.db = db;
    }

    public void lock(LocalDate date) {
        db.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "app:schedule:mtr:" + date);
    }

    public boolean exists(LocalDate date) {
        return db.fetchOne("""
                SELECT EXISTS (SELECT 1 FROM app.trip t
                    JOIN app.route r ON r.id = t.route_id
                    JOIN app.line l ON l.id = r.line_id
                    JOIN app.operator o ON o.id = l.operator_id
                    WHERE o.code = 'mtr' AND t.service_date = ?)
                """, date).get(0, Boolean.class);
    }

    public List<RouteStop> routeStops() {
        return db.fetch("""
                SELECT r.id, r.code, l.code AS line_code, s.seq, s.distance_m
                FROM app.route_stop s JOIN app.route r ON r.id = s.route_id
                JOIN app.line l ON l.id = r.line_id
                JOIN app.operator o ON o.id = l.operator_id
                WHERE o.code = 'mtr' ORDER BY r.code, s.seq
                """).map(r -> new RouteStop(r.get("id", Long.class), r.get("code", String.class),
                        r.get("line_code", String.class), r.get("seq", Integer.class), r.get("distance_m", Double.class)));
    }

    public void insert(LocalDate date, List<PlannedTrip> trips) {
        var ids = db.fetch("SELECT nextval(pg_get_serial_sequence('app.trip', 'id')) FROM generate_series(1, ?)",
                trips.size()).getValues(0, Long.class);
        var tripBatch = db.batch(db.query("""
                INSERT INTO app.trip (id, route_id, service_date, code, source, source_key, plan_type)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, 'mtr-simulator', ?, 'simulated')
                """, new Object[5]));
        var stopBatch = db.batch(db.query("""
                INSERT INTO app.trip_stop (trip_id, route_id, seq, arrival_at, departure_at)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, new Object[5]));
        for (int i = 0; i < trips.size(); i++) {
            var trip = trips.get(i);
            long id = ids.get(i);
            tripBatch.bind(id, trip.routeId(), date, trip.code(), trip.code());
            for (var stop : trip.stops()) {
                stopBatch.bind(id, trip.routeId(), stop.seq(),
                        stop.arrivalAt() == null ? null : stop.arrivalAt().atOffset(ZoneOffset.UTC),
                        stop.departureAt() == null ? null : stop.departureAt().atOffset(ZoneOffset.UTC));
            }
        }
        tripBatch.execute();
        stopBatch.execute();
    }
}
