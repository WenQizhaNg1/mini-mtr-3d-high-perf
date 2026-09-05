package dev.minimtr.config;

import dev.minimtr.TestSettings;
import dev.minimtr.service.ScheduleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchedulePreparationTest {
    @Test
    void preparesCurrentAndNextServiceDateBeforeDawn() {
        var service = mock(ScheduleService.class);
        var clock = Clock.fixed(Instant.parse("2026-09-04T21:00:00Z"), ZoneOffset.UTC);
        new SchedulePreparation(service, TestSettings.load(), clock).run(null);
        var ordered = inOrder(service);
        ordered.verify(service).ensure(LocalDate.parse("2026-09-04"));
        ordered.verify(service).ensure(LocalDate.parse("2026-09-05"));
    }

    @Test
    void startupFailurePropagatesAndPeriodicFailureCanRetry() {
        var service = mock(ScheduleService.class);
        var clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        var date = LocalDate.parse("2026-09-05");
        when(service.ensure(date)).thenThrow(new IllegalStateException("Missing route stops"))
                .thenThrow(new IllegalStateException("Temporary database failure")).thenReturn(false);
        var preparation = new SchedulePreparation(service, TestSettings.load(), clock);
        assertThrows(IllegalStateException.class, () -> preparation.run(null));
        assertDoesNotThrow(preparation::refresh);
        preparation.refresh();
        verify(service).ensure(date.plusDays(1));
    }
}
