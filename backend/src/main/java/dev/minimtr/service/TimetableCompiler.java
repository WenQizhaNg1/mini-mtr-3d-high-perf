package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.TransitNetwork;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class TimetableCompiler {
    public record Trip(String key, long routeId, String pattern, String type, List<PlannedTrip.Stop> stops) {}

    public List<Trip> compile(TransitConfig config, TransitNetwork network, LocalDate day, List<TimetableTrip> timetable) {
        var result = new ArrayList<Trip>();
        var keys = new HashSet<String>();
        var zone = ZoneId.of(config.timezone());
        for (var trip : timetable) {
            require(trip!=null,"Timetable trip cannot be null");
            if (trip.serviceDate() != null && !trip.serviceDate().isBlank() && !day.toString().equals(trip.serviceDate())) continue;
            require(trip.key() != null && !trip.key().isBlank() && keys.add(trip.key()), "Duplicate or missing trip key: " + trip.key());
            var path = path(network, trip.pattern());
            require(trip.stops() != null && trip.stops().size() == path.stops().size(), "Trip " + trip.key() + " must provide every stop in its pattern");
            var calls = new ArrayList<PlannedTrip.Stop>();
            Instant previous = null;
            for (int i = 0; i < trip.stops().size(); i++) {
                var call = trip.stops().get(i);
                require(call!=null,"Missing stop in trip "+trip.key());
                require(call.seq() == i, "Trip " + trip.key() + ": stop sequence must be 0-based and contiguous");
                var arrival = ServiceTime.parse(day, call.arrival(), zone);
                var departure = ServiceTime.parse(day, call.departure(), zone);
                require(i == 0 || arrival != null, "Missing arrival at " + trip.key() + " stop " + i);
                require(i == trip.stops().size() - 1 || departure != null, "Missing departure at " + trip.key() + " stop " + i);
                require(arrival == null || departure == null || !arrival.isAfter(departure), "Departure precedes arrival at " + trip.key() + " stop " + i);
                require(previous == null || arrival != null && arrival.isAfter(previous), "Non-positive running time at " + trip.key() + " stop " + i);
                if (previous != null) {
                    double speed = (path.stops().get(i).distanceMeters() - path.stops().get(i - 1).distanceMeters())
                            / (Duration.between(previous, arrival).toMillis() / 1000.0);
                    require(speed <= 200, "Implausible speed at " + trip.key() + " stop " + i + ": " + Math.round(speed * 3.6) + " km/h");
                }
                calls.add(new PlannedTrip.Stop(i, arrival, departure));
                previous = departure;
            }
            String type=trip.type()==null?"timetable":trip.type();
            require(Set.of("timetable","simulated","frequency").contains(type),"Invalid plan type");
            result.add(new Trip(trip.key(), path.id(), path.code(), type, List.copyOf(calls)));
        }
        for (var rule : config.simulation().rules()) {
            require(rule!=null,"Simulation rule cannot be null");
            var path = path(network, rule.pattern());
            require(rule.id() != null && !rule.id().isBlank(), "Rule id is required");
            require(Double.isFinite(rule.headwaySeconds()) && rule.headwaySeconds() >= 1, "Headway must be at least 1 second");
            require(Double.isFinite(rule.speedKmph()) && rule.speedKmph() > 0 && rule.speedKmph() <= 720, "Invalid rule speed");
            require(Double.isFinite(rule.dwellSeconds()) && rule.dwellSeconds() >= 0, "Invalid dwell duration");
            var first = ServiceTime.parse(day, rule.first(), zone);
            var last = ServiceTime.parse(day, rule.last(), zone);
            require(first != null && last != null && !last.isBefore(first), "Rule end must follow start; use 24:xx for next day");
            int seq = 0;
            for (var at = first; !at.isAfter(last); at = at.plusMillis(Math.round(rule.headwaySeconds() * 1000))) {
                require(result.size() < 10000, "Schedule exceeds 10000 trips per service day");
                String key = rule.id() + ":" + seq++;
                require(keys.add(key), "Duplicate trip/rule key: " + key);
                var calls = new ArrayList<PlannedTrip.Stop>();
                var departure = at;
                Instant arrival = null;
                for (int i = 0; i < path.stops().size(); i++) {
                    calls.add(new PlannedTrip.Stop(i, arrival, i == path.stops().size() - 1 ? null : departure));
                    if (i < path.stops().size() - 1) {
                        double distance = path.stops().get(i + 1).distanceMeters() - path.stops().get(i).distanceMeters();
                        arrival = departure.plusMillis(Math.max(1, Math.round(distance / (rule.speedKmph() / 3.6) * 1000)));
                        departure = arrival.plusMillis(Math.round(rule.dwellSeconds() * 1000));
                    }
                }
                result.add(new Trip(key, path.id(), path.code(), "simulated", List.copyOf(calls)));
            }
        }
        require(result.size() <= 10000, "Schedule exceeds 10000 trips per service day");
        var serviceStart=ServiceTime.parse(day,config.simulation().serviceDayStart(),zone);
        var limit=ServiceTime.parse(day.plusDays(3),config.simulation().serviceDayStart(),zone);
        for(var trip:result) {
            var first=trip.stops().getFirst();
            var last=trip.stops().getLast();
            var starts=first.arrivalAt()==null?first.departureAt():first.arrivalAt();
            var ends=last.departureAt()==null?last.arrivalAt():last.departureAt();
            require(!starts.isBefore(serviceStart),"Trip "+trip.key()+" starts before service-day boundary; use 24:xx for the following day");
            require(!ends.isAfter(limit),"Trip "+trip.key()+" exceeds the three-day service window");
        }
        return List.copyOf(result);
    }
    private TransitNetwork.Path path(TransitNetwork network, String code) {
        require(code!=null,"Pattern reference is required");
        var path = network.paths().get(code);
        require(path != null, "Unknown pattern: " + code);
        return path;
    }
}
