package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.PlannedTrip;
import dev.minimtr.model.entity.RouteStop;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ScheduleGenerator {
    private final ServiceSettings settings;

    public ScheduleGenerator(ServiceSettings settings) {
        this.settings = settings;
    }

    public List<PlannedTrip> generate(LocalDate date, List<RouteStop> routeStops) {
        var routes = routeStops.stream().collect(Collectors.groupingBy(RouteStop::routeCode));
        routes.values().forEach(stops -> stops.sort(Comparator.comparingInt(RouteStop::seq)));
        var start = settings.startsAt(date);
        long end = start.plusSeconds(settings.serviceEndOffsetMinutes() * 60L).toEpochMilli();
        var localStart = OffsetTime.parse(settings.serviceDayStart());
        int startMinute = localStart.getHour() * 60 + localStart.getMinute();
        var result = new ArrayList<PlannedTrip>();
        for (var line : settings.lines()) {
            for (var direction : List.of("UP", "DOWN")) {
                double offset = line.firstTrainOffsetMinutes() + (direction.equals("DOWN") ? 1.5 : 0);
                int sequence = 0;
                while (offset <= line.lastTrainOffsetMinutes()) {
                    String pattern = line.patterns().get(direction).choose(++sequence);
                    var stops = routes.get(pattern);
                    if (stops == null || stops.size() < 2 || !stops.getFirst().lineCode().equals(line.lineId())) {
                        throw new IllegalStateException("Missing or incorrect route stops for " + pattern);
                    }
                    var times = new ArrayList<PlannedTrip.Stop>();
                    // Keep fractional milliseconds between legs, matching the legacy generator's Date conversion.
                    double departure = start.toEpochMilli() + offset * 60_000;
                    Instant arrival = null;
                    for (int i = 0; i < stops.size() - 1; i++) {
                        var from = stops.get(i);
                        var to = stops.get(i + 1);
                        double distance = to.distanceM() - from.distanceM();
                        if (!Double.isFinite(distance) || distance <= 0 || from.seq() >= to.seq()) {
                            throw new IllegalStateException("Non-increasing route distance/sequence: " + pattern);
                        }
                        times.add(new PlannedTrip.Stop(from.seq(), arrival, Instant.ofEpochMilli((long) departure)));
                        double nextArrival = departure + distance / (line.speedKmph() * 1000 / 3600)
                                * settings.travelTimeFactor() * 1000;
                        arrival = Instant.ofEpochMilli((long) nextArrival);
                        if (!arrival.isAfter(times.getLast().departureAt())) {
                            throw new IllegalStateException("Travel time is below millisecond precision: " + pattern);
                        }
                        departure = nextArrival + settings.dwellSeconds() * 1000;
                    }
                    times.add(new PlannedTrip.Stop(stops.getLast().seq(), arrival, null));
                    if (arrival.toEpochMilli() <= end) {
                        String code = date + ":" + line.lineId() + ":" + direction + ":"
                                + String.format(Locale.ROOT, "%03d", sequence);
                        result.add(new PlannedTrip(code, stops.getFirst().routeId(), times));
                    }
                    offset += headway(startMinute + offset, line.headways());
                }
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("No schedule generated for " + date);
        return List.copyOf(result);
    }

    static double headway(double minute, ServiceSettings.Headways headways) {
        minute %= 1440;
        if ((minute >= 420 && minute < 570) || (minute >= 1050 && minute < 1170)) return headways.peak();
        if (minute >= 1170 && minute < 1290) return headways.evening();
        if (minute >= 1290 || minute < 90) return headways.late();
        return headways.normal();
    }
}
