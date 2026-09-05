package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.TransitNetwork;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Standard observations are pushed through the authenticated ingestion endpoint. */
@Component
public class FeedTransitAdapter implements TransitAdapter {
    private final Map<String,List<RealtimeObservation>> latest = new ConcurrentHashMap<>();
    public String code() { return "feed"; }
    public Set<String> modes() { return Set.of("simulation","realtime"); }
    public List<TimetableTrip> loadTimetable(TransitConfig config, TransitNetwork network, LocalDate day) {
        return config.simulation().timetable();
    }
    public List<RealtimeObservation> readRealtime(String operator, TransitConfig config) {
        return latest.getOrDefault(operator,List.of());
    }
    public void accept(String operator,List<RealtimeObservation> observations) { latest.put(operator,List.copyOf(observations)); }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void changed(TransitChanged event) { latest.remove(event.operator()); }
}
