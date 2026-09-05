package dev.minimtr.service;

import java.time.*;
import static dev.minimtr.service.DatasetParser.require;

public final class ServiceTime {
    private ServiceTime() {}
    public static Instant parse(LocalDate day, String time, ZoneId zone) {
        if (time == null || time.isBlank()) return null;
        try {
            if (time.contains("T")) return OffsetDateTime.parse(time).toInstant();
            var parts = time.split(":");
            require(parts.length == 2 || parts.length == 3, "Expected HH:mm[:ss]: " + time);
            int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]), s = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            require(h >= 0 && h < 72 && m >= 0 && m < 60 && s >= 0 && s < 60, "Invalid service time: " + time);
            var local = day.plusDays(h / 24).atTime(h % 24, m, s);
            var offsets = zone.getRules().getValidOffsets(local);
            require(offsets.size() == 1, "Ambiguous or missing local time; supply an ISO time with offset: " + time);
            return local.toInstant(offsets.getFirst());
        } catch (NumberFormatException | DateTimeException error) {
            throw DatasetParser.bad("Invalid service time " + time + ": " + error.getMessage());
        }
    }
    public static LocalDate serviceDate(Instant at, String start, ZoneId zone) {
        var day = at.atZone(zone).toLocalDate();
        return at.isBefore(parse(day, start, zone)) ? day.minusDays(1) : day;
    }
}
