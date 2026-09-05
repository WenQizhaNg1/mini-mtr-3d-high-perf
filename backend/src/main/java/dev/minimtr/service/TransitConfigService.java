package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.repo.TransitRepo;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class TransitConfigService {
    private final TransitRepo repo;
    private final TransitMappingService mapping;
    private final TimetableCompiler compiler;
    private final Map<String,TransitAdapter> adapters;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    public TransitConfigService(TransitRepo repo,TransitMappingService mapping,TimetableCompiler compiler,
            List<TransitAdapter> adapters,Clock clock,ApplicationEventPublisher events) {
        this.repo=repo; this.mapping=mapping; this.compiler=compiler; this.clock=clock; this.events=events;
        this.adapters=adapters.stream().collect(Collectors.toUnmodifiableMap(TransitAdapter::code,a -> a));
    }
    public List<TransitRepo.Configured> list() { return repo.configs(); }
    public List<Map<String,Object>> adapters() {
        return adapters.values().stream().sorted(Comparator.comparing(TransitAdapter::code))
                .map(a -> Map.<String,Object>of("code",a.code(),"modes",a.modes())).toList();
    }
    public TransitRepo.Configured get(String code) {
        return repo.configs().stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown operator: " + code));
    }
    public TransitAdapter adapter(String code) {
        var adapter=adapters.get(code);
        require(adapter!=null,"Unknown adapter: " + code);
        return adapter;
    }
    @Transactional
    public TransitRepo.Configured save(String code,TransitConfig input) {
        require(code!=null && code.matches("[a-z][a-z0-9-]{0,63}"),"Invalid operator code");
        var config=validate(input);
        long id=repo.save(code,config);
        rebuild(new TransitRepo.Configured(code,id,config));
        return get(code);
    }
    private TransitConfig validate(TransitConfig input) {
        require(input!=null,"Configuration is required");
        require(input.name()!=null && !input.name().isBlank(),"Operator name is required");
        try { ZoneId.of(input.timezone()); } catch(Exception e) { throw DatasetParser.bad("Invalid IANA timezone"); }
        require(adapter(input.adapter()).modes().contains(input.mode()),"Adapter does not support mode " + input.mode());
        var m=input.mapping();
        require(m!=null && m.patterns()!=null && m.routeFields()!=null && m.stationFields()!=null,"Mapping is required");
        require(Double.isFinite(m.snapToleranceMeters()) && m.snapToleranceMeters()>=0 && m.snapToleranceMeters()<=2000,"Snap tolerance must be 0–2000 metres");
        var s=input.simulation()==null?new TransitConfig.Simulation("00:00",List.of(),List.of()):input.simulation();
        require(s!=null && s.serviceDayStart()!=null && s.rules()!=null && s.timetable()!=null,"Simulation parameters are required");
        require(s.serviceDayStart().matches("([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?"),"Service-day start must be HH:mm[:ss]");
        var r=input.realtime()==null?new TransitConfig.Realtime(List.of(),45):input.realtime();
        require(r.monitors()!=null && r.staleAfterSeconds()>=5 && r.staleAfterSeconds()<=600,"Realtime freshness must be 5–600 seconds");
        return new TransitConfig(input.name(),input.timezone(),input.adapter(),input.mode(),m,s,r);
    }
    @Transactional
    public void remove(String code) {
        var configured=get(code);
        repo.lock(configured.id());
        repo.remove(configured.id());
        events.publishEvent(new TransitChanged(code));
    }
    @EventListener
    public void dataChanged(DataChanged event) {
        for(var configured:repo.configs()) {
            var m=configured.config().mapping();
            if(Objects.equals(m.routesDataset(),event.dataset()) || Objects.equals(m.stationsDataset(),event.dataset())) rebuild(configured);
        }
    }
    private void rebuild(TransitRepo.Configured configured) {
        repo.lock(configured.id());
        var days=new TreeSet<>(repo.days(configured.id()));
        var c=configured.config();
        var today=ServiceTime.serviceDate(clock.instant(),c.simulation().serviceDayStart(),ZoneId.of(c.timezone()));
        days.add(today); days.add(today.plusDays(1));
        if(c.mode().equals("simulation")) for(var trip:c.simulation().timetable()) {
            require(trip!=null,"Timetable trip cannot be null");
            if(trip.serviceDate()!=null && !trip.serviceDate().isBlank()) {
            try { days.add(LocalDate.parse(trip.serviceDate())); }
            catch(DateTimeException error) { throw DatasetParser.bad("Invalid trip service date: "+trip.serviceDate()); }
            }
        }
        mapping.rebuild(configured.id(),c.mapping());
        if(c.mode().equals("simulation")) for(var day:days) prepare(configured,day);
        events.publishEvent(new TransitChanged(configured.code()));
    }
    @Transactional
    public void ensure(String code,LocalDate date) {
        var configured=get(code);
        repo.lock(configured.id());
        if(!repo.hasDay(configured.id(),date)) prepare(configured,date);
    }
    private void prepare(TransitRepo.Configured configured,LocalDate day) {
        var network=repo.network(configured.id());
        var c=configured.config();
        var adapter=adapter(c.adapter());
        if(!adapter.modes().contains("simulation")) return;
        repo.insertPlans(configured.code(),day,compiler.compile(c,network,day,adapter.loadTimetable(c,network,day)));
    }
}
