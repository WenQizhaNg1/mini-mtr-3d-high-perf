package dev.minimtr.model.dto;

import dev.minimtr.model.entity.MotionNode;
import java.util.List;
import java.util.Map;

public record MotionCheckpoint(int version, long savedAt, List<State> states, Map<String, Long> sources) {
    public record Observation(String station, double eta, long sourceAt) {}
    public record State(String id, String patternId, String pathHash, List<MotionNode> nodes,
            List<MotionNode> plan, Observation observation, boolean conflict, boolean retired) {}
}
