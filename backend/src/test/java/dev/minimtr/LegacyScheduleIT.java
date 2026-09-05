package dev.minimtr;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.repo.ScheduleRepo;
import dev.minimtr.service.ScheduleGenerator;
import dev.minimtr.service.ScheduleService;
import dev.minimtr.service.TrainService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
@EnabledIfSystemProperty(named = "legacy.verify", matches = "true")
class LegacyScheduleIT {
    @Autowired DSLContext db;
    @Autowired ScheduleRepo repo;
    @Autowired ScheduleGenerator generator;
    @Autowired ScheduleService schedules;
    @Autowired TrainService trains;
    @Autowired ServiceSettings settings;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    private LocalDate sourceDate() {
        return db.fetchSingle("SELECT min(service_date) FROM mtr.train_runs").get(0, LocalDate.class);
    }

    @Test
    void generatedTimetableMatchesEveryLegacyLegToTheMillisecond() {
        var date = sourceDate();
        var generated = generator.generate(date, repo.routeStops());
        var oldRuns = db.fetch("SELECT id, pattern_id FROM mtr.train_runs WHERE service_date = ?", date)
                .intoMap("id", "pattern_id");
        var routeCodes = repo.routeStops().stream().collect(java.util.stream.Collectors.toMap(
                s -> s.routeId(), s -> s.routeCode(), (a, b) -> a));
        Map<String, Record> legs = new HashMap<>();
        db.fetch("""
                SELECT leg.* FROM mtr.train_legs leg JOIN mtr.train_runs run ON run.id = leg.train_id
                WHERE run.service_date = ?
                """, date).forEach(r -> legs.put(r.get("train_id") + "/" + r.get("from_stop_sequence"), r));
        assertEquals(oldRuns.size(), generated.size());
        int compared = 0;
        for (var trip : generated) {
            assertEquals(oldRuns.get(trip.code()), routeCodes.get(trip.routeId()));
            for (int i = 0; i < trip.stops().size() - 1; i++) {
                var from = trip.stops().get(i);
                var to = trip.stops().get(i + 1);
                var old = legs.get(trip.code() + "/" + from.seq());
                assertNotNull(old, trip.code());
                assertEquals(old.get("departure_at", OffsetDateTime.class).toInstant(), from.departureAt());
                assertEquals(old.get("arrival_at", OffsetDateTime.class).toInstant(), to.arrivalAt());
                assertEquals(old.get("to_stop_sequence", Integer.class), to.seq());
                compared++;
            }
        }
        assertEquals(legs.size(), compared);
    }

    @Test
    @Transactional
    void insertsOneCompleteDayThenLeavesItUnchanged() {
        var date = db.fetchSingle("SELECT max(service_date) FROM app.trip").get(0, LocalDate.class).plusDays(1);
        assertTrue(schedules.ensure(date));
        var trips = db.fetch("SELECT id, source_key FROM app.trip WHERE service_date = ? ORDER BY id", date);
        assertEquals(generator.generate(date, repo.routeStops()).size(), trips.size());
        assertFalse(schedules.ensure(date));
        assertEquals(trips, db.fetch("SELECT id, source_key FROM app.trip WHERE service_date = ? ORDER BY id", date));
        assertEquals(0, db.fetchSingle("""
                SELECT count(*)::int FROM app.trip t WHERE t.service_date = ?
                    AND (SELECT count(*) FROM app.trip_stop s WHERE s.trip_id = t.id)
                        <> (SELECT count(*) FROM app.route_stop rs WHERE rs.route_id = t.route_id)
                """, date).get(0, Integer.class));
        assertFalse(trains.load(settings.startsAt(date).plusSeconds(3600)).trains().isEmpty());
        // The test transaction rolls back all generated plans.
    }

    @Test
    void existingImportedDayIsNotOverwritten() {
        assertFalse(schedules.ensure(sourceDate()));
    }

    @Test
    void failedBatchRollsBackBothTripsAndStops() {
        var date = db.fetchSingle("SELECT max(service_date) FROM app.trip").get(0, LocalDate.class).plusDays(1);
        var valid = generator.generate(date, repo.routeStops()).getFirst();
        var invalid = new dev.minimtr.model.dto.PlannedTrip(valid.code() + ":invalid", valid.routeId(),
                java.util.List.of(new dev.minimtr.model.dto.PlannedTrip.Stop(-1, null, settings.startsAt(date))));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                transaction.executeWithoutResult(status -> repo.insert(date, java.util.List.of(valid, invalid))));
        assertFalse(repo.exists(date));
    }

    @Test
    void javaPositionsMatchLegacyPostgisWithoutRealtimeOffsets() throws Exception {
        var migration = new ClassPathResource("legacy/0001_train-times.sql").getContentAsString(StandardCharsets.UTF_8);
        // Use the original SQL as an independent oracle, with delays disabled for planned replay.
        var reference = migration.split("\\$function\\$")[1]
                .replace("mtr.realtime_offsets AS offsets", "(SELECT NULL::text AS train_id, 0::float8 AS delay_seconds WHERE false) AS offsets")
                .replace("p_at", "{0}");
        var start = settings.startsAt(sourceDate());
        var first = db.fetchSingle("""
                SELECT leg.departure_at, leg.arrival_at FROM mtr.train_legs leg
                JOIN mtr.train_runs run ON run.id = leg.train_id
                WHERE run.service_date = ? AND leg.sequence = 0 ORDER BY leg.departure_at LIMIT 1
                """, sourceDate());
        var arrival = first.get("arrival_at", OffsetDateTime.class).toInstant();
        var times = java.util.List.of(start, start.plusSeconds(3600), start.plusSeconds(7200),
                start.plusSeconds(54000), arrival.minusMillis(1), arrival,
                arrival.plusSeconds((long) settings.dwellSeconds()).minusMillis(1),
                arrival.plusSeconds((long) settings.dwellSeconds()), start.plusSeconds(1110 * 60L));
        int compared = 0;
        for (Instant at : times) {
            var expected = db.fetch(reference, DSL.val(at.atOffset(ZoneOffset.UTC)));
            var actual = trains.load(at).trains();
            assertEquals(expected.size(), actual.size(), at.toString());
            for (int i = 0; i < expected.size(); i++) {
                var old = expected.get(i);
                var train = actual.get(i);
                assertEquals(old.get("id"), train.id());
                assertEquals(old.get("line_id"), train.lineId());
                assertEquals(old.get("pattern_id"), train.patternId());
                assertEquals(old.get("colour", String.class).toLowerCase(java.util.Locale.ROOT),
                        train.colour().toLowerCase(java.util.Locale.ROOT));
                assertEquals(old.get("state"), train.state());
                assertEquals(old.get("previous_station"), train.previousStation());
                assertEquals(old.get("next_station"), train.nextStation());
                assertEquals(old.get("destination_station"), train.destinationStation());
                assertEquals(old.get("previous_time", OffsetDateTime.class).toInstant(), train.previousTime());
                assertEquals(old.get("next_time", OffsetDateTime.class).toInstant(), train.nextTime());
                assertEquals(old.get("lng", Double.class), train.lng(), 1e-9);
                assertEquals(old.get("lat", Double.class), train.lat(), 1e-9);
                double difference = Math.abs(old.get("bearing", Double.class) - train.bearing());
                assertTrue(Math.min(difference, 360 - difference) < 1e-4, "Heading mismatch: " + train.id());
                compared++;
            }
        }
        assertTrue(compared > 100);
    }
}
