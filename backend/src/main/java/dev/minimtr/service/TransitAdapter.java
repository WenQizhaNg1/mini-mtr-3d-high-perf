package dev.minimtr.service;

import dev.minimtr.model.dto.*;
import dev.minimtr.model.entity.TransitNetwork;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface TransitAdapter {
    String code();
    Set<String> modes();
    List<TimetableTrip> loadTimetable(TransitConfig config, TransitNetwork network, LocalDate day);
    List<RealtimeObservation> readRealtime(String operator, TransitConfig config) throws Exception;
}
