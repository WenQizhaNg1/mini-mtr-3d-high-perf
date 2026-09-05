package dev.minimtr.model.dto;

import java.util.List;

/** Local service-day times (including 24:xx) or explicit ISO instants. */
public record TimetableTrip(String key, String pattern, String serviceDate, List<Call> stops, String type) {
    public TimetableTrip(String key, String pattern, String serviceDate, List<Call> stops) {
        this(key,pattern,serviceDate,stops,"timetable");
    }
    public record Call(int seq, String arrival, String departure) {}
}
