package dev.minimtr.model.vo;

import java.time.Instant;
import java.time.LocalDate;

public record ServiceDayVo(LocalDate serviceDate, boolean active, Instant startsAt,
        Instant endsAt, Replay replay, Schedules schedules) {
    public record Replay(Instant startsAt, Instant endsAt) {}
    public record Schedules(boolean current, LocalDate nextServiceDate, boolean next) {}
}
