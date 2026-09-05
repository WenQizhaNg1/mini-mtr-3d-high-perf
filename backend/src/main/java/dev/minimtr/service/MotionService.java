package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.Arrival;
import dev.minimtr.model.vo.TrainSnapshotVo;
import dev.minimtr.repo.MotionRepo;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MotionService {
    private static final Logger log = LoggerFactory.getLogger(MotionService.class);
    private final MotionRepo repo;
    private final ServiceSettings settings;
    private final Clock clock;
    private Connection owner;
    private Map<String, MotionPath> paths;
    private MotionEngine engine;
    private long refreshedAt, savedAt;

    public MotionService(MotionRepo repo, ServiceSettings settings, Clock clock) {
        this.repo = repo; this.settings = settings; this.clock = clock;
    }

    public synchronized void start() throws SQLException {
        owner = repo.acquireOwner();
        try {
            paths = repo.paths();
            engine = new MotionEngine(paths, settings.lines().stream().collect(Collectors.toMap(
                    ServiceSettings.Line::lineId, line -> line.speedKmph() / 3.6)));
            long now = clock.millis();
            engine.sync(repo.runs(paths, now), now);
            var checkpoint = repo.checkpoint(owner);
            if (checkpoint != null) log.info("Restored {} live trajectories", engine.restore(checkpoint, now));
            refreshedAt = now; savedAt = 0;
        } catch (SQLException | RuntimeException error) {
            try { repo.releaseOwner(owner); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            owner = null;
            throw error;
        }
    }

    public synchronized TrainSnapshotVo snapshot() throws SQLException {
        if (owner == null || !owner.isValid(2)) throw new IllegalStateException("Live database ownership lost; restart the Java backend");
        long now = clock.millis();
        if (now - refreshedAt >= 60_000) {
            engine.sync(repo.runs(paths, now), now); refreshedAt = now;
        }
        var snapshot = engine.snapshot(now);
        if (now - savedAt >= 10_000) save();
        return snapshot;
    }

    public synchronized int observe(ServiceSettings.Line line, String direction, List<Arrival> arrivals,
            long sourceAt, long now) throws SQLException {
        var choice = line.patterns().get(direction);
        var patterns = new HashSet<String>(); patterns.add(choice.defaultPattern());
        if (choice.alternate() != null) patterns.add(choice.alternate());
        int count = engine.observe(line.lineId(), line.monitorStation(), direction, patterns, arrivals, sourceAt, now);
        return count;
    }

    public synchronized void save() throws SQLException {
        long now = clock.millis();
        repo.save(owner, engine.checkpoint(now)); savedAt = now;
    }

    public synchronized void close() throws SQLException {
        if (owner == null) return;
        try { save(); }
        finally {
            try { repo.releaseOwner(owner); }
            finally { owner = null; }
        }
    }
}
