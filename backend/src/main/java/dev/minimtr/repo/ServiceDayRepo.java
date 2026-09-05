package dev.minimtr.repo;

import dev.minimtr.model.entity.ServiceDayState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class ServiceDayRepo {
    private final DSLContext db;

    public ServiceDayRepo(DSLContext db) {
        this.db = db;
    }

    public ServiceDayState load(LocalDate date) {
        return db.fetchOne("""
                SELECT min(s.departure_at) AS starts_at, max(s.arrival_at) AS ends_at,
                    EXISTS (SELECT 1 FROM app.trip WHERE service_date = ?) AS current_exists,
                    EXISTS (SELECT 1 FROM app.trip WHERE service_date = ?) AS next_exists
                FROM app.trip_stop s
                """, date, date.plusDays(1)).map(r -> new ServiceDayState(
                        instant(r.get("starts_at", OffsetDateTime.class)),
                        instant(r.get("ends_at", OffsetDateTime.class)),
                        r.get("current_exists", Boolean.class), r.get("next_exists", Boolean.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
