package dev.minimtr.service;

import dev.minimtr.model.entity.TrainWindow;
import dev.minimtr.repo.TrainRepo;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainServiceTest {
    @Test
    void interpolatesRunningAndKeepsDwellAtStationWithCachedPath() throws Exception {
        var repo = mock(TrainRepo.class);
        var service = new TrainService(repo);
        var departure = Instant.parse("2026-09-05T00:00:30Z");
        var nextArrival = departure.plusSeconds(60);
        var window = new TrainWindow("train", "T", "route", "#123456", 1, "A", "B", "C",
                0.25, 0.75, departure.minusSeconds(30), departure, nextArrival);
        when(repo.windowsAt(any())).thenReturn(List.of(window));
        when(repo.path(1)).thenReturn(new WKBWriter().write(new WKTReader().read("LINESTRING(114 22,114.04 22)")));
        var running = service.load(departure.plusSeconds(30)).trains().getFirst();
        assertEquals("running", running.state());
        assertEquals(114.02, running.lng(), 1e-10);
        assertEquals(departure, running.previousTime());
        var dwell = service.load(departure.minusSeconds(1)).trains().getFirst();
        assertEquals("dwell", dwell.state());
        assertEquals(114.01, dwell.lng(), 1e-10);
        assertEquals(window.arrivalAt(), dwell.previousTime());
        assertEquals(nextArrival, dwell.nextTime());
        verify(repo, times(1)).path(1);
    }
}
