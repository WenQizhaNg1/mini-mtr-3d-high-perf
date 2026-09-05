package dev.minimtr.service;

import dev.minimtr.TestSettings;
import dev.minimtr.model.dto.MtrResponse;
import dev.minimtr.repo.MtrClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeServiceTest {
    @Test
    void validatesSourceTimeNormalizesArrivalRowsAndMaintainsIncidents() throws Exception {
        var settings = TestSettings.load(); var line = settings.lines().getFirst();
        var motion = mock(MotionService.class);
        var service = new RealtimeService(mock(MtrClient.class), motion, settings, Clock.systemUTC(), true);
        var now = Instant.parse("2026-09-05T00:00:00Z");
        var station = new MtrResponse.Station("2026-09-05 08:00:00", List.of(
                new MtrResponse.Eta("CHW", "2026-09-05 08:03:00", "Y"),
                new MtrResponse.Eta("CHW", "invalid", "Y"),
                new MtrResponse.Eta("CHW", "2026-09-05 08:02:00", "N")), List.of());
        var data = Map.of(line.apiCode() + "-" + line.monitorStation(), station);
        service.accept(line, new MtrResponse(1, "Successful", null, "Y", null, data), now);
        verify(motion).observe(eq(line), eq("UP"), argThat(a -> a.size() == 1), eq(now.toEpochMilli()), eq(now.toEpochMilli()));
        assertNotNull(service.operations().lines().getFirst().incident());
        service.accept(line, new MtrResponse(1, "Successful", null, "N", null, data), now);
        assertNull(service.operations().lines().getFirst().incident());
        assertThrows(IllegalArgumentException.class, () -> service.accept(line,
                new MtrResponse(1, "Successful", null, "N", "invalid", Map.of()), now));
        assertEquals(0, service.accept(line, new MtrResponse(1, "successful", null, "N", "-",
                Map.of(line.apiCode() + "-" + line.monitorStation(), new MtrResponse.Station("-", null, null))), now));
        assertThrows(IllegalArgumentException.class, () -> service.accept(line,
                new MtrResponse(1, "successful", null, "N", "-", Map.of(line.apiCode() + "-" + line.monitorStation(),
                        new MtrResponse.Station("-", station.up(), null))), now));
        assertNull(RealtimeService.parseTime("2026-02-30 08:00:00"));
        assertNull(RealtimeService.incident(new MtrResponse(0, "Service has ended", null, "N", null, null), now));
    }
}
