package dev.minimtr;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.repo.MotionRepo;
import dev.minimtr.service.MotionEngine;
import java.time.LocalDate;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
@EnabledIfSystemProperty(named = "legacy.verify", matches = "true")
class LiveDatabaseIT {
    @Autowired MotionRepo repo;
    @Autowired DSLContext db;
    @Autowired ServiceSettings settings;

    @Test
    void ownsSingleSessionAndRoundTripsCheckpointWithoutOverwritingExistingState() throws Exception {
        var date = db.fetchSingle("SELECT min(service_date) FROM app.trip").get(0, LocalDate.class);
        long now = settings.startsAt(date).plusSeconds(3600).toEpochMilli();
        var paths = repo.paths();
        var runs = repo.runs(paths, now);
        var speeds = settings.lines().stream().collect(Collectors.toMap(ServiceSettings.Line::lineId, l -> l.speedKmph() / 3.6));
        var engine = new MotionEngine(paths, speeds);
        engine.sync(runs, now);
        var snapshot = engine.snapshot(now);
        assertFalse(snapshot.trains().isEmpty());
        assertTrue(snapshot.trains().stream().allMatch(t -> t.motion() != null && t.motion().size() >= 2));
        var owner = repo.acquireOwner();
        try {
            assertThrows(IllegalStateException.class, repo::acquireOwner);
            owner.setAutoCommit(false);
            repo.save(owner, engine.checkpoint(now));
            var checkpoint = repo.checkpoint(owner);
            var restored = new MotionEngine(paths, speeds);
            restored.sync(runs, now);
            assertTrue(restored.restore(checkpoint, now + 1000) > 0);
            assertEquals(engine.snapshot(now + 1000), restored.snapshot(now + 1000));
        } finally {
            owner.rollback(); owner.setAutoCommit(true); repo.releaseOwner(owner);
        }
        var nextOwner = repo.acquireOwner();
        repo.releaseOwner(nextOwner);
    }
}
