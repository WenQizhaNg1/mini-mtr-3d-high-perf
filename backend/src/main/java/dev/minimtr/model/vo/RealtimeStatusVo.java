package dev.minimtr.model.vo;

import java.time.Instant;

public record RealtimeStatusVo(boolean enabled, Boolean healthy, Instant lastPollAt, String lastError, int updatedTrains) {}
