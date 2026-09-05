package dev.minimtr.model.entity;

import java.time.Instant;

public record TrainWindow(String id, String lineId, String patternId, String colour, long objectId,
        String station, String nextStation, String destinationStation,
        double fraction, double nextFraction, Instant arrivalAt, Instant departureAt, Instant nextArrivalAt) {}
