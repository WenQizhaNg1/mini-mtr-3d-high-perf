package dev.minimtr.model.dto;

import java.util.List;
import java.util.Map;

public record TransitConfig(String name, String timezone, String adapter, String mode,
        Mapping mapping, Simulation simulation, Realtime realtime) {
    public record Mapping(String routesDataset, String stationsDataset,
            Map<String,String> routeFields, Map<String,String> stationFields, List<Pattern> patterns,
            double snapToleranceMeters) {}
    public record Pattern(String code, String featureKey, String direction, boolean reversed, List<Stop> stops) {}
    public record Stop(String stationKey, Double fraction) {}
    public record Simulation(String serviceDayStart, List<Rule> rules, List<TimetableTrip> timetable) {}
    public record Rule(String id, String pattern, String first, String last, double headwaySeconds,
            double speedKmph, double dwellSeconds) {}
    public record Realtime(List<Monitor> monitors, int staleAfterSeconds) {}
    public record Monitor(String line, String station) {}
}
