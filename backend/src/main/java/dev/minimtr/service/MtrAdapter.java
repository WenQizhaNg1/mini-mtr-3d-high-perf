package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.*;
import dev.minimtr.repo.MtrClient;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MtrAdapter implements TransitAdapter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final MtrClient client;
    private final ServiceSettings settings;
    public MtrAdapter(MtrClient client,ServiceSettings settings) { this.client=client; this.settings=settings; }
    public String code() { return "mtr"; }
    public Set<String> modes() { return Set.of("simulation","realtime"); }
    public List<TimetableTrip> loadTimetable(TransitConfig config,TransitNetwork network,LocalDate day) {
        if (!config.simulation().timetable().isEmpty() || !config.simulation().rules().isEmpty())
            return config.simulation().timetable();
        // MTR's bundled service rules are an input source for simulation only.
        if (network.paths().isEmpty()) return List.of();
        var stops = new ArrayList<RouteStop>();
        var patterns = new HashMap<Long,String>();
        for (var path:network.paths().values()) {
            patterns.put(path.id(),path.code());
            for (var stop:path.stops()) stops.add(new RouteStop(path.id(),path.code(),path.lineId(),stop.seq(),stop.distanceMeters()));
        }
        var rules = new ServiceSettings(1,config.simulation().serviceDayStart() + "+08:00",
                settings.serviceEndOffsetMinutes(),settings.dwellSeconds(),settings.travelTimeFactor(),settings.lines());
        return new ScheduleGenerator(rules).generate(day,stops).stream().map(t -> new TimetableTrip(t.code(),
                patterns.get(t.routeId()),day.toString(),t.stops().stream().map(s -> new TimetableTrip.Call(s.seq(),
                    s.arrivalAt()==null?null:s.arrivalAt().toString(),s.departureAt()==null?null:s.departureAt().toString())).toList(),"simulated")).toList();
    }
    public List<RealtimeObservation> readRealtime(String operator,TransitConfig config) throws Exception {
        var monitors = config.realtime().monitors();
        if (monitors.isEmpty()) monitors = settings.lines().stream().map(l -> new TransitConfig.Monitor(l.apiCode(),l.monitorStation())).toList();
        var result=new ArrayList<RealtimeObservation>();
        for (var monitor:monitors) {
            var response=client.schedule(monitor.line(),monitor.station());
            if (!Objects.equals(response.status(),1)) throw new IllegalStateException(monitor.line() + ": " + response.message());
            var station=response.data()==null?null:response.data().get(monitor.line()+"-"+monitor.station());
            if(station==null) continue;
            if((station.up()==null || station.up().isEmpty()) && (station.down()==null || station.down().isEmpty())) continue;
            var observed=time(station.currentTime()==null?response.currentTime():station.currentTime());
            for (String direction:List.of("UP","DOWN")) {
                var rows=direction.equals("UP")?station.up():station.down();
                if(rows==null) continue;
                int i=0;
                for(var row:rows) {
                    if("N".equals(row.valid())) continue;
                    result.add(new RealtimeObservation("eta",monitor.line()+":"+monitor.station()+":"+direction+":"+i++,null,
                            monitor.line(),null,observed,null,null,null,null,monitor.station(),row.dest(),time(row.time())));
                }
            }
        }
        return List.copyOf(result);
    }
    private Instant time(String value) { return LocalDateTime.parse(value,TIME).toInstant(ZoneOffset.ofHours(8)); }
}
