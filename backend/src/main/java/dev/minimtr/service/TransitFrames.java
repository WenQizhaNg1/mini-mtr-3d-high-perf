package dev.minimtr.service;

import dev.minimtr.model.dto.TransitChanged;
import dev.minimtr.model.entity.TransitNetwork;
import dev.minimtr.model.vo.*;
import dev.minimtr.repo.TransitRepo;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class TransitFrames {
    private final TransitRepo repo;
    private final TransitConfigService configs;
    private final RealtimeFrames realtime;
    private final Clock clock;
    private final Map<String,TransitNetwork> networks=new ConcurrentHashMap<>();
    private record Window(String operator,LocalDate day) {}
    // Keep independent live/replay windows without retaining unlimited historical plans.
    private final Map<Window,List<dev.minimtr.model.entity.PlannedRun>> plans=Collections.synchronizedMap(new LinkedHashMap<>(8,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Window,List<dev.minimtr.model.entity.PlannedRun>> entry) { return size()>8; }
    });
    public TransitFrames(TransitRepo repo,TransitConfigService configs,RealtimeFrames realtime,Clock clock) {
        this.repo=repo; this.configs=configs; this.realtime=realtime; this.clock=clock;
    }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void changed(TransitChanged event) {
        networks.remove(event.operator());
        synchronized(plans) { plans.keySet().removeIf(key -> key.operator().equals(event.operator())); }
    }
    @org.springframework.transaction.annotation.Transactional
    public MotionFrame frame(String operator,String mode,Instant at) {
        repo.lock(configs.get(operator).id());
        var configured=configs.get(operator);
        String selected=mode==null?configured.config().mode():mode;
        require(configs.adapter(configured.config().adapter()).modes().contains(selected),"Unsupported mode: " + selected);
        var network=networks.computeIfAbsent(operator,k -> repo.network(configured.id()));
        if(selected.equals("realtime")) {
            require(at==null,"Realtime has no historical replay; select simulation explicitly");
            return realtime.frame(configured,network);
        }
        Instant time=at==null?clock.instant():at;
        var c=configured.config();
        LocalDate day=ServiceTime.serviceDate(time,c.simulation().serviceDayStart(),ZoneId.of(c.timezone()));
        var key=new Window(operator,day);
        var window=plans.get(key);
        if(window==null) {
            for(int i=0;i<3;i++) configs.ensure(operator,day.minusDays(i));
            window=repo.runs(operator,configured.id(),day.minusDays(2),day);
            plans.put(key,window);
        }
        var trains=window.stream()
                .filter(run -> run.nodes().getFirst().at()<=time.toEpochMilli() && run.nodes().getLast().at()>time.toEpochMilli())
                .map(run -> MotionSampling.sample(run,network.paths().get(run.patternId()),time)).toList();
        return new MotionFrame(operator,"simulation",time,trains,List.of(),"ok","");
    }
    public Map<String,Object> network(String operator) {
        var c=configs.get(operator);
        var result=new LinkedHashMap<>(repo.catalog(c));
        result.put("modes",configs.adapter(c.config().adapter()).modes());
        return result;
    }
    public ServiceDayVo serviceDay(String operator,Instant at) {
        var configured=configs.get(operator);
        var c=configured.config();
        var zone=ZoneId.of(c.timezone());
        var time=at==null?clock.instant():at;
        var day=ServiceTime.serviceDate(time,c.simulation().serviceDayStart(),zone);
        var start=ServiceTime.parse(day,c.simulation().serviceDayStart(),zone);
        var end=ServiceTime.parse(day.plusDays(1),c.simulation().serviceDayStart(),zone);
        return new ServiceDayVo(day,true,start,end,new ServiceDayVo.Replay(start,end),
                new ServiceDayVo.Schedules(repo.hasDay(configured.id(),day),day.plusDays(1),repo.hasDay(configured.id(),day.plusDays(1))));
    }
}
