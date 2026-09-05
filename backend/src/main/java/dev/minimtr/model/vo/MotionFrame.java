package dev.minimtr.model.vo;

import java.time.Instant;
import java.util.List;

public record MotionFrame(String operator, String mode, Instant timestamp, List<TrainVo> trains,
        List<Arrival> arrivals, String status, String error) {
    public record Arrival(String id, String vehicleId, String lineId, String stationId, String destinationId,
            Instant observedAt, Instant eta, boolean stale) {}
}
