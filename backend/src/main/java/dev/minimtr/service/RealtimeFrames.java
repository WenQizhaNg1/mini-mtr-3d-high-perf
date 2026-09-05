package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.TransitNetwork;
import dev.minimtr.model.vo.*;
import dev.minimtr.repo.TransitRepo;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import jakarta.annotation.PreDestroy;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class RealtimeFrames {
    private static final class Source {
        volatile List<RealtimeObservation> observations=List.of();
        volatile String error="Waiting for source";
        long nextPoll;
        boolean polling;
    }
    private final Map<String,Source> sources=new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newVirtualThreadPerTaskExecutor();
    private final TransitConfigService configs;
    private final TransitRepo repo;
    private final FeedTransitAdapter feed;
    private final Clock clock;
    public RealtimeFrames(TransitConfigService configs,TransitRepo repo,FeedTransitAdapter feed,Clock clock) {
        this.configs=configs; this.repo=repo; this.feed=feed; this.clock=clock;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(TransitChanged event) { sources.remove(event.operator()); }

    @org.springframework.transaction.annotation.Transactional
    public void ingest(String operator,List<RealtimeObservation> observations) {
        repo.lock(configs.get(operator).id());
        var configured=configs.get(operator);
        require(configured.config().adapter().equals("feed"),"Select the feed adapter to ingest observations");
        validate(observations,repo.network(configured.id()));
        var old=feed.readRealtime(operator,configured.config()).stream()
                .collect(java.util.stream.Collectors.toMap(RealtimeObservation::id,o -> o));
        for(var observation:observations) {
            var previous=old.get(observation.id());
            require(previous==null || !observation.observedAt().isBefore(previous.observedAt()),"Out-of-order observation: " + observation.id());
        }
        feed.accept(operator,observations);
    }
    public MotionFrame frame(TransitRepo.Configured configured,TransitNetwork network) {
        var config=configured.config();
        var adapter=configs.adapter(config.adapter());
        require(adapter.modes().contains("realtime"),"Adapter has no realtime capability");
        var source=sources.computeIfAbsent(configured.code(),k -> new Source());
        if(adapter.code().equals("feed")) {
            source.observations=feed.readRealtime(configured.code(),config);
            source.error="";
        } else synchronized(source) {
            if(!source.polling && clock.millis()>=source.nextPoll) {
                source.polling=true;
                source.nextPoll=clock.millis()+30_000;
                worker.submit(() -> {
                    try {
                        var observations=adapter.readRealtime(configured.code(),config);
                        validate(observations,network);
                        source.observations=List.copyOf(observations);
                        source.error="";
                    } catch(Exception error) {
                        if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                        source.error=Objects.requireNonNullElse(error.getMessage(),error.getClass().getSimpleName());
                    } finally { synchronized(source) { source.polling=false; } }
                });
            }
        }
        Instant now=clock.instant();
        var trains=new ArrayList<TrainVo>();
        var arrivals=new ArrayList<MotionFrame.Arrival>();
        boolean stale=false;
        for(var observation:source.observations) {
            Instant until=observation.observedAt().plusSeconds(config.realtime().staleAfterSeconds());
            boolean expired=now.isAfter(until);
            stale|=expired;
            if(observation.kind().equals("eta")) {
                arrivals.add(new MotionFrame.Arrival(configured.code()+":"+observation.id(),observation.vehicleId(),
                        observation.lineId(),observation.stationId(),observation.destinationId(),observation.observedAt(),observation.eta(),expired));
                continue;
            }
            var path=observation.patternId()==null?null:network.paths().get(observation.patternId());
            RoutePath.Position position;
            if(observation.kind().equals("gps")) position=new RoutePath.Position(observation.lng(),observation.lat(),
                    observation.bearing()==null?0:observation.bearing());
            else {
                // If a geometry edit invalidates a previous distance observation, wait for the source to locate it again.
                if(path==null || observation.distanceMeters()>path.geometry().lengthMeters()) continue;
                position=path.geometry().locateMetric(observation.distanceMeters());
            }
            String colour=network.paths().values().stream().filter(p -> p.lineId().equals(observation.lineId()))
                    .findFirst().map(TransitNetwork.Path::colour).orElse("#168b92");
            trains.add(new TrainVo(configured.code()+":"+observation.vehicleId(),observation.lineId(),
                    observation.patternId()==null?"":observation.patternId(),colour,position.lng(),position.lat(),position.bearing(),
                    "unknown","",null,path==null?"":path.stops().getLast().code(),0,null,null,expired?"stale":"observed",null,
                    observation.observedAt(),until));
        }
        String status=!source.error.isEmpty()?"unavailable":stale?"stale":source.observations.isEmpty()?"empty":"ok";
        return new MotionFrame(configured.code(),"realtime",now,List.copyOf(trains),List.copyOf(arrivals),status,source.error);
    }
    private void validate(List<RealtimeObservation> observations,TransitNetwork network) {
        require(observations!=null && observations.size()<=10000,"Expected at most 10000 observations");
        var lines=new HashSet<String>();
        var stations=new HashSet<String>();
        network.paths().values().forEach(p -> { lines.add(p.lineId()); p.stops().forEach(s -> stations.add(s.code())); });
        var ids=new HashSet<String>();
        var vehicles=new HashSet<String>();
        for(var o:observations) {
            require(o!=null && o.kind()!=null && Set.of("gps","metric","eta").contains(o.kind()),"Unknown observation kind");
            require(o.id()!=null && !o.id().isBlank() && ids.add(o.id()),"Missing or duplicate observation id");
            require(o.observedAt()!=null && !o.observedAt().isAfter(clock.instant().plusSeconds(10)),"Invalid observation timestamp: "+o.id());
            require(lines.contains(o.lineId()),"Unknown observation line: "+o.lineId());
            if(o.kind().equals("eta")) {
                require(stations.contains(o.stationId()) && o.eta()!=null,"Invalid ETA: "+o.id());
            } else {
                require(o.vehicleId()!=null && !o.vehicleId().isBlank() && vehicles.add(o.vehicleId()),"Position needs a unique vehicle identity");
                if(o.kind().equals("gps")) {
                    if(o.patternId()!=null) {
                        var path=network.paths().get(o.patternId());
                        require(path!=null && path.lineId().equals(o.lineId()),"Invalid GPS pattern reference");
                    }
                    require(finite(o.lng()) && finite(o.lat()) && Math.abs(o.lng())<=180 && Math.abs(o.lat())<=85.05112878,"Invalid GPS coordinates");
                    require(o.bearing()==null || finite(o.bearing()) && o.bearing()>=0 && o.bearing()<360,"Invalid bearing");
                } else {
                    var path=o.patternId()==null?null:network.paths().get(o.patternId());
                    require(path!=null && path.lineId().equals(o.lineId()) && finite(o.distanceMeters()) &&
                            o.distanceMeters()>=0 && o.distanceMeters()<=path.geometry().lengthMeters(),"Invalid along-path observation");
                }
            }
        }
    }
    private boolean finite(Double value) { return value!=null && Double.isFinite(value); }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
