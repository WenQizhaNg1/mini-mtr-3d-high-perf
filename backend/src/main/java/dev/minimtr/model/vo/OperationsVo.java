package dev.minimtr.model.vo;

import java.time.Instant;
import java.util.List;

public record OperationsVo(RealtimeStatusVo realtime, List<Line> lines) {
    public record Line(String lineId, Incident incident) {}
    public record Incident(String message, String url, boolean isDelay, Instant updatedAt) {}
}
