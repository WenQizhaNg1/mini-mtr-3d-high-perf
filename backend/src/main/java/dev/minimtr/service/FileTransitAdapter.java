package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.TransitNetwork;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FileTransitAdapter implements TransitAdapter {
    public String code() { return "file"; }
    public Set<String> modes() { return Set.of("simulation"); }
    public List<TimetableTrip> loadTimetable(TransitConfig config, TransitNetwork network, LocalDate day) {
        return config.simulation().timetable();
    }
    public List<RealtimeObservation> readRealtime(String operator, TransitConfig config) {
        throw new UnsupportedOperationException("File adapter has no realtime source");
    }
}
