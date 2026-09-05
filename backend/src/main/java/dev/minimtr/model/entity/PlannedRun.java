package dev.minimtr.model.entity;

import java.util.List;

public record PlannedRun(String id, String patternId, List<MotionNode> nodes) {
    public PlannedRun { nodes = List.copyOf(nodes); }
}
