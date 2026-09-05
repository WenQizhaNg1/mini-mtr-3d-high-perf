package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.entity.ServiceDayState;
import dev.minimtr.repo.ServiceDayRepo;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceDayServiceTest {
    private final ServiceDayRepo repo = mock(ServiceDayRepo.class);
    private final ServiceSettings settings = dev.minimtr.TestSettings.load();
    private final ServiceDayService service = new ServiceDayService(repo, settings);

    @Test
    void beforeBoundaryUsesPreviousServiceDate() {
        var date = LocalDate.parse("2026-09-04");
        when(repo.load(date)).thenReturn(new ServiceDayState(null, null, false, true));
        var result = service.load(Instant.parse("2026-09-04T21:29:59Z"));
        assertEquals(date, result.serviceDate());
        assertFalse(result.active());
        assertNull(result.replay().startsAt());
        assertTrue(result.schedules().next());
    }

    @Test
    void startIsInclusiveAndEndIsExclusive() {
        var date = LocalDate.parse("2026-09-05");
        when(repo.load(date)).thenReturn(new ServiceDayState(null, null, true, false));
        var atStart = service.load(Instant.parse("2026-09-04T21:30:00Z"));
        assertEquals(date, atStart.serviceDate());
        assertTrue(atStart.active());
        assertEquals(Instant.parse("2026-09-05T16:00:00Z"), atStart.endsAt());
        assertFalse(service.load(atStart.endsAt()).active());
    }
}
