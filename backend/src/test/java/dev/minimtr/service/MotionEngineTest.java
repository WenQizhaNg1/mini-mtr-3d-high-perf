package dev.minimtr.service;

import dev.minimtr.model.dto.Arrival;
import dev.minimtr.model.dto.MotionCheckpoint;
import dev.minimtr.model.dto.MotionCheckpoint.Observation;
import dev.minimtr.model.entity.MotionNode;
import dev.minimtr.model.entity.PlannedRun;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class MotionEngineTest {
    private static final long EPOCH = 1_788_566_400_000L;
    private final MotionPath path;
    private final List<MotionNode> nodes;

    MotionEngineTest() throws Exception {
        var geometry = new RoutePath(new WKBWriter().write(new WKTReader().read("LINESTRING(114 22,114.005 22,114.005 22.005)")));
        path = new MotionPath("test", "TWL", "#123456", "B", geometry);
        double middle = geometry.metricVertices()[1], end = geometry.metricVertices()[2];
        nodes = List.of(new MotionNode(EPOCH, 0, "A"), new MotionNode(EPOCH + 100_000, middle, "MOK"),
                new MotionNode(EPOCH + 130_000, middle, "MOK"), new MotionNode(EPOCH + 230_000, end, "B"));
    }

    private MotionEngine engine() {
        var engine = new MotionEngine(Map.of("test", path), Map.of("TWL", 20.0));
        engine.sync(List.of(new PlannedRun("one", "test", nodes)), EPOCH);
        return engine;
    }

    private int observe(MotionEngine engine, double eta, long source, long now) {
        return engine.observe("TWL", "MOK", "UP", Set.of("test"), List.of(new Arrival(eta, "B")), source, now);
    }

    @Test
    void etaChangesPreservePositionHistoryDwellAndSpeedLimits() {
        for (int change : List.of(-30_000, 30_000, 120_000)) {
            var engine = engine(); var state = engine.states.get("one"); long now = EPOCH + 20_000;
            double current = MotionEngine.progress(state.nodes, now);
            assertTrue(MotionEngine.retime(state, "MOK", EPOCH + 100_000 + change, now, 20));
            assertEquals(current, MotionEngine.progress(state.nodes, now), 1e-9);
            assertEquals(MotionEngine.progress(nodes, now - 10_000), MotionEngine.progress(state.nodes, now - 10_000), 1e-9);
            var stops = state.nodes.stream().filter(n -> n.station().equals("MOK")).toList();
            assertEquals(30_000, stops.get(1).at() - stops.getFirst().at(), 1e-6);
            for (int i = 1; i < state.nodes.size(); i++) {
                var a = state.nodes.get(i - 1); var b = state.nodes.get(i);
                assertTrue(b.distance() >= a.distance());
                assertTrue((b.distance() - a.distance()) / ((b.at() - a.at()) / 1000) <= 20);
            }
        }
    }

    @Test
    void impossibleEtaLeavesTheOldTrajectoryIntact() {
        var state = engine().states.get("one"); var before = state.nodes;
        assertFalse(MotionEngine.retime(state, "MOK", EPOCH + 21_000, EPOCH + 20_000, 20));
        assertEquals(before, state.nodes);
        assertFalse(MotionEngine.retime(state, "A", EPOCH + 100_000, EPOCH + 20_000, 20));
    }

    @Test
    void matchingSurvivesPollsAndRejectsDuplicateOldAndFutureSources() {
        var engine = engine(); long first = EPOCH + 10_000;
        assertEquals(1, observe(engine, EPOCH + 130_000, first, first));
        var before = engine.states.get("one").nodes;
        assertEquals(0, observe(engine, EPOCH + 150_000, first, first + 1000));
        assertEquals(0, observe(engine, EPOCH + 150_000, first - 1, first + 1000));
        assertEquals(0, observe(engine, EPOCH + 150_000, first + 50_000, first + 1000));
        assertEquals(before, engine.states.get("one").nodes);
        long now = first + 30_000;
        double d = MotionEngine.progress(before, now);
        assertEquals(1, observe(engine, EPOCH + 160_000, now, now));
        assertEquals(d, MotionEngine.progress(engine.states.get("one").nodes, now), 1e-9);
    }

    @Test
    void closeCandidatesAndWrongDestinationsAreNotForced() {
        var engine = engine();
        engine.sync(List.of(new PlannedRun("two", "test", nodes.stream()
                .map(n -> new MotionNode(n.at() + 10_000, n.distance(), n.station())).toList())), EPOCH);
        assertEquals(0, observe(engine, EPOCH + 105_000, EPOCH + 1000, EPOCH + 1000));
        assertEquals(0, engine.observe("TWL", "MOK", "UP", Set.of("test"),
                List.of(new Arrival(EPOCH + 105_000, "C")), EPOCH + 2000, EPOCH + 2000));
    }

    @Test
    void staleObservationFreezesAndReconnectResumesFromFrozenPosition() {
        var engine = engine(); var state = engine.states.get("one");
        state.nodes = nodes.stream().map(n -> new MotionNode(EPOCH + (n.at() - EPOCH) * 4, n.distance(), n.station())).toList();
        state.observation = new Observation("MOK", EPOCH + 400_000, EPOCH);
        var frozen = engine.snapshot(EPOCH + 190_000).trains().getFirst();
        assertEquals("stale", frozen.estimate());
        assertEquals(frozen.lng(), engine.snapshot(EPOCH + 200_000).trains().getFirst().lng());
        double d = MotionEngine.progress(state.nodes, EPOCH + 180_000);
        assertTrue(MotionEngine.retime(state, "MOK", EPOCH + 450_000, EPOCH + 210_000, 20));
        state.observation = new Observation("MOK", EPOCH + 450_000, EPOCH + 210_000);
        assertEquals(d, MotionEngine.progress(state.nodes, EPOCH + 210_000), 1e-9);
        assertTrue(MotionEngine.progress(state.nodes, EPOCH + 211_000) > d);
    }

    @Test
    void bendsAndFutureSurviveJsonCheckpointAndRetiredTrainsStayRetired() {
        var engine = engine(); var state = engine.states.get("one");
        double end = path.geometry().metricVertices()[2];
        state.nodes = List.of(new MotionNode(EPOCH, 0, "A"), new MotionNode(EPOCH + 100_000, end, "B"));
        double crossing = EPOCH + path.geometry().metricVertices()[1] / end * 100_000;
        long now = (long) crossing - 1000;
        var snapshot = engine.snapshot(now);
        assertTrue(snapshot.trains().getFirst().motion().stream().anyMatch(p ->
                Math.abs(p.at() - crossing) < 0.01 && Math.abs(p.lng() - 114.005) < 1e-10 && Math.abs(p.lat() - 22) < 1e-10));
        var mapper = JsonMapper.builder().build();
        var saved = mapper.readValue(mapper.writeValueAsString(engine.checkpoint(now)), MotionCheckpoint.class);
        var restored = engine();
        assertEquals(1, restored.restore(saved, now + 2000));
        assertEquals(engine.snapshot(now + 2000), restored.snapshot(now + 2000));
        assertTrue(engine.snapshot(EPOCH + 101_000).trains().isEmpty());
        engine.sync(List.of(new PlannedRun("one", "test", nodes)), EPOCH + 102_000);
        assertTrue(engine.snapshot(EPOCH + 102_000).trains().isEmpty());
    }

    @Test
    void passingTheMonitorContinuesAsForecastWithoutResettingToPlan() {
        var engine = engine();
        assertEquals(1, observe(engine, EPOCH + 150_000, EPOCH + 10_000, EPOCH + 10_000));
        assertEquals("predicted", engine.snapshot(EPOCH + 210_000).trains().getFirst().estimate());
        var state = engine.states.get("one");
        assertNotEquals(MotionEngine.progress(state.plan, EPOCH + 210_000), MotionEngine.progress(state.nodes, EPOCH + 210_000));
    }

    @Test
    void changedGeometryOrPlanDoesNotRestoreOldTrajectory() throws Exception {
        var saved = engine().checkpoint(EPOCH);
        var changed = new MotionPath("test", "TWL", "#123456", "B", new RoutePath(new WKBWriter().write(
                new WKTReader().read("LINESTRING(114 22,114.005 22.005,114.005 22)"))));
        var engine = new MotionEngine(Map.of("test", changed), Map.of("TWL", 20.0));
        engine.sync(List.of(new PlannedRun("one", "test", nodes)), EPOCH);
        assertEquals(0, engine.restore(saved, EPOCH + 1000));
        assertEquals(0, engine.restore(saved, EPOCH - 1000));
        assertEquals(0, engine.restore(saved, EPOCH + 86_400_001));
    }
}
