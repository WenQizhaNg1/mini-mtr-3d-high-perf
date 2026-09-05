package dev.minimtr.model.entity;

import java.time.Instant;

public record ServiceDayState(Instant replayStartsAt, Instant replayEndsAt,
        boolean currentExists, boolean nextExists) {}
