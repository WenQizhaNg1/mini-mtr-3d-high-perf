package dev.minimtr.service;

import dev.minimtr.model.dto.Arrival;
import dev.minimtr.model.dto.MotionCheckpoint;
import dev.minimtr.model.dto.MotionCheckpoint.Observation;
import dev.minimtr.model.entity.MotionNode;
import dev.minimtr.model.entity.PlannedRun;
import dev.minimtr.model.vo.TrainSnapshotVo;
import dev.minimtr.model.vo.TrainVo;
import java.time.Instant;
import java.util.*;

/** Single-owner state machine. MotionService serializes all access. */
public final class MotionEngine {
    private static final long TTL = 180_000;
    private static final long MATCH_WINDOW = 120_000;
    private static final long AMBIGUITY_MARGIN = 20_000;
    final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Long> sources = new HashMap<>();
    private final Map<String, MotionPath> paths;
    private final Map<String, Double> maxSpeeds;

    static final class State {
        final String id, patternId, pathHash;
        final List<MotionNode> plan;
        List<MotionNode> nodes;
        Observation observation;
        boolean conflict, retired;

        State(PlannedRun run, String pathHash, long now) {
            id = run.id(); patternId = run.patternId(); this.pathHash = pathHash;
            plan = run.nodes(); nodes = plan;
            retired = plan.getLast().at() < now;
        }
    }

    public MotionEngine(Map<String, MotionPath> paths, Map<String, Double> maxSpeeds) {
        this.paths = Map.copyOf(paths);
        this.maxSpeeds = Map.copyOf(maxSpeeds);
    }

    public void sync(List<PlannedRun> runs, long now) {
        var ids = new HashSet<String>();
        runs.forEach(run -> ids.add(run.id()));
        states.values().removeIf(s -> !ids.contains(s.id) && s.plan.getLast().at() < now - 7_200_000);
        for (var run : runs) {
            if (!validNodes(run.nodes())) throw new IllegalArgumentException("Invalid motion plan: " + run.id());
            states.computeIfAbsent(run.id(), id -> new State(run, paths.get(run.patternId()).geometry().fingerprint(), now));
        }
    }

    static double progress(List<MotionNode> nodes, double at) {
        if (at <= nodes.getFirst().at()) return nodes.getFirst().distance();
        for (int i = 1; i < nodes.size(); i++) {
            var a = nodes.get(i - 1); var b = nodes.get(i);
            if (at <= b.at()) return a.distance() + (b.distance() - a.distance()) * (at - a.at()) / (b.at() - a.at());
        }
        return nodes.getLast().distance();
    }

    private static double predictionTime(State state, double at) {
        var observation = state.observation;
        if (observation == null || observation.eta() <= observation.sourceAt() + TTL) return at;
        return Math.min(at, observation.sourceAt() + TTL);
    }

    static boolean retime(State state, String station, double eta, long now, double maxSpeed) {
        double prediction = predictionTime(state, now);
        double current = progress(state.nodes, prediction);
        double anchorAt = Math.max(now, state.nodes.getFirst().at());
        double shift = now - prediction;
        var future = state.nodes.stream().map(n -> new MotionNode(n.at() + shift, n.distance(), n.station(), n.anchor()))
                .filter(n -> n.at() > anchorAt && n.distance() >= current).toList();
        int target = -1;
        for (int i = 0; i < future.size(); i++) {
            if (future.get(i).station().equals(station) && future.get(i).distance() > current) { target = i; break; }
        }
        if (target < 0 || eta <= anchorAt) return false;
        String previousStation = state.nodes.getFirst().station();
        for (var node : state.nodes) if (node.at() <= prediction) previousStation = node.station();
        boolean artificial = state.nodes.stream().noneMatch(n -> n.at() == anchorAt && n.distance() == current && !n.anchor());
        var anchor = new MotionNode(anchorAt, current, previousStation, artificial);
        double dwell = 0, running = 0;
        for (int i = 0; i <= target; i++) {
            var a = i == 0 ? anchor : future.get(i - 1); var b = future.get(i);
            if (a.distance() == b.distance()) dwell += b.at() - a.at();
            else running += b.at() - a.at();
        }
        double factor = (eta - anchorAt - dwell) / running;
        if (!(factor > 0) || !Double.isFinite(factor)) return false;
        var nodes = new ArrayList<MotionNode>();
        double historyEnd = prediction < now ? prediction : anchorAt;
        state.nodes.stream().filter(n -> n.at() < historyEnd).forEach(nodes::add);
        if (prediction < now) nodes.add(new MotionNode(prediction, current, previousStation, artificial));
        nodes.add(anchor);
        for (int i = 0; i < future.size(); i++) {
            var oldA = i == 0 ? anchor : future.get(i - 1); var oldB = future.get(i);
            double dt = (oldB.at() - oldA.at()) * (i <= target && oldB.distance() > oldA.distance() ? factor : 1);
            if (dt <= 0 || (oldB.distance() - oldA.distance()) / (dt / 1000) > maxSpeed) return false;
            nodes.add(new MotionNode(nodes.getLast().at() + dt, oldB.distance(), oldB.station(), oldB.anchor()));
        }
        state.nodes = List.copyOf(nodes);
        return true;
    }

    private record Candidate(State state, double delta, double order, boolean tracked) {}

    public int observe(String lineId, String station, String direction, Set<String> patterns,
            List<Arrival> arrivals, long sourceAt, long now) {
        String key = lineId + ":" + direction + ":" + station;
        if (sourceAt <= sources.getOrDefault(key, 0L) || sourceAt < now - TTL || sourceAt > now + 10_000) return 0;
        sources.put(key, sourceAt);
        var unused = new LinkedHashSet<State>();
        states.values().stream().filter(s -> !s.retired && patterns.contains(s.patternId)).forEach(unused::add);
        int count = 0;
        double previousOrder = Double.NEGATIVE_INFINITY;
        for (var arrival : arrivals) {
            if (arrival.destination() == null || arrival.destination().isBlank()) continue;
            var candidates = new ArrayList<Candidate>();
            for (var state : unused) {
                if (!paths.get(state.patternId).destination().equals(arrival.destination())) continue;
                double current = progress(state.nodes, predictionTime(state, now));
                var target = state.nodes.stream().filter(n -> n.station().equals(station) && n.distance() > current).findFirst();
                var plan = state.plan.stream().filter(n -> n.station().equals(station)).findFirst();
                if (target.isEmpty() || plan.isEmpty() || plan.get().at() <= previousOrder) continue;
                double predicted = (state.observation != null && state.observation.station().equals(station)
                        ? state.observation.eta() : target.get().at()) + now - predictionTime(state, now);
                double delta = Math.abs(predicted - arrival.at());
                if (delta <= MATCH_WINDOW) candidates.add(new Candidate(state, delta, plan.get().at(), state.observation != null));
            }
            candidates.sort(Comparator.comparing(Candidate::tracked).reversed().thenComparingDouble(Candidate::delta));
            if (candidates.isEmpty()) continue;
            var best = candidates.getFirst();
            if (candidates.size() > 1) {
                var second = candidates.get(1);
                if (best.tracked() == second.tracked() && Math.abs(second.delta() - best.delta()) < AMBIGUITY_MARGIN) continue;
            }
            unused.remove(best.state()); previousOrder = best.order();
            boolean accepted = retime(best.state(), station, arrival.at(), now, maxSpeeds.get(lineId));
            best.state().conflict = !accepted;
            if (accepted) {
                best.state().observation = new Observation(station, arrival.at(), sourceAt);
                count++;
            }
        }
        return count;
    }

    public TrainSnapshotVo snapshot(long now) {
        var trains = new ArrayList<TrainVo>();
        for (var state : states.values()) {
            if (state.retired || now < state.nodes.getFirst().at()) continue;
            double at = predictionTime(state, now), d = progress(state.nodes, at);
            if (d >= state.nodes.getLast().distance()) { state.retired = true; continue; }
            var path = paths.get(state.patternId);
            var previous = state.nodes.getFirst(); var segmentStart = previous;
            for (var node : state.nodes) if (node.at() <= at) {
                segmentStart = node;
                if (!node.anchor()) previous = node;
            }
            var segmentEnd = state.nodes.stream().filter(n -> n.at() > at).findFirst().orElseThrow();
            var next = state.nodes.stream().filter(n -> n.distance() > d).findFirst().orElseThrow();
            var planned = state.plan.stream().filter(n -> n.station().equals(next.station())).findFirst().orElseThrow();
            double validUntil = predictionTime(state, Math.min(now + 3000, state.nodes.getLast().at()));
            var times = new TreeSet<Double>();
            times.add((double) now); times.add(Math.max(now, validUntil));
            for (var node : state.nodes) if (node.at() > now && node.at() < validUntil) times.add(node.at());
            for (int i = 1; i < state.nodes.size(); i++) {
                var a = state.nodes.get(i - 1); var b = state.nodes.get(i);
                if (b.at() < now || a.at() > validUntil || a.distance() == b.distance()) continue;
                for (double vertex : path.geometry().metricVertices()) {
                    if (vertex <= a.distance() || vertex >= b.distance()) continue;
                    double time = a.at() + (vertex - a.distance()) / (b.distance() - a.distance()) * (b.at() - a.at());
                    if (time > now && time < validUntil) times.add(time);
                }
            }
            var position = path.geometry().locateMetric(d);
            String estimate = at < now ? "stale" : state.conflict ? "conflict" : state.observation == null ? "planned"
                    : now >= state.observation.eta() ? "predicted" : "observed";
            var motion = times.stream().map(time -> {
                var p = path.geometry().locateMetric(progress(state.nodes, predictionTime(state, time)));
                return new TrainVo.MotionPoint(time, p.lng(), p.lat(), p.bearing());
            }).toList();
            trains.add(new TrainVo(state.id, path.lineId(), path.id(), path.colour(), position.lng(), position.lat(),
                    position.bearing(), segmentStart.distance() == segmentEnd.distance() ? "dwell" : "running",
                    previous.station(), next.station(), path.destination(), (next.at() - planned.at()) / 1000,
                    Instant.ofEpochMilli((long) previous.at()), Instant.ofEpochMilli((long) next.at()), estimate, motion));
        }
        return new TrainSnapshotVo(Instant.ofEpochMilli(now), List.copyOf(trains));
    }

    public MotionCheckpoint checkpoint(long now) {
        var saved = states.values().stream().map(s -> new MotionCheckpoint.State(s.id, s.patternId, s.pathHash,
                s.nodes, s.plan, s.observation, s.conflict, s.retired)).toList();
        return new MotionCheckpoint(2, now, saved, Map.copyOf(sources));
    }

    public int restore(MotionCheckpoint checkpoint, long now) {
        if (checkpoint.version() != 2 || checkpoint.savedAt() > now || now - checkpoint.savedAt() > 86_400_000) return 0;
        int restored = 0;
        for (var saved : checkpoint.states()) {
            var current = states.get(saved.id());
            if (current == null || !current.patternId.equals(saved.patternId()) || !current.pathHash.equals(saved.pathHash())
                    || !current.plan.equals(saved.plan())) continue;
            if (!validNodes(saved.nodes()) || saved.nodes().getLast().distance() != current.plan.getLast().distance()) {
                throw new IllegalArgumentException("Invalid checkpoint trajectory: " + saved.id());
            }
            current.nodes = List.copyOf(saved.nodes()); current.observation = saved.observation();
            current.conflict = saved.conflict(); current.retired = saved.retired(); restored++;
        }
        sources.clear(); sources.putAll(checkpoint.sources());
        return restored;
    }

    private static boolean validNodes(List<MotionNode> nodes) {
        if (nodes == null || nodes.size() < 2) return false;
        for (int i = 0; i < nodes.size(); i++) {
            var n = nodes.get(i);
            if (!Double.isFinite(n.at()) || !Double.isFinite(n.distance()) || n.station() == null || n.distance() < 0) return false;
            if (i > 0 && (n.at() <= nodes.get(i - 1).at() || n.distance() < nodes.get(i - 1).distance())) return false;
        }
        return true;
    }
}
