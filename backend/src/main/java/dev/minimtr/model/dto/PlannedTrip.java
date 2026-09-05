package dev.minimtr.model.dto;

import java.time.Instant;
import java.util.List;

public record PlannedTrip(String code, long routeId, List<Stop> stops) {
    public PlannedTrip {
        stops = List.copyOf(stops);
    }

    public record Stop(int seq, Instant arrivalAt, Instant departureAt) {}
}
