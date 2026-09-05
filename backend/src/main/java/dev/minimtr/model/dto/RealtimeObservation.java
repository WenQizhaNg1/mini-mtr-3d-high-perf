package dev.minimtr.model.dto;

import java.time.Instant;

/** ETA is a prediction about a station, never a position measurement. */
public record RealtimeObservation(String kind, String id, String vehicleId, String lineId, String patternId,
        Instant observedAt, Double lng, Double lat, Double bearing, Double distanceMeters,
        String stationId, String destinationId, Instant eta) {}
