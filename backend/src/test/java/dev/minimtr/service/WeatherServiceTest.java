package dev.minimtr.service;

import dev.minimtr.repo.WeatherClient;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeatherServiceTest {
    private static final String READING = """
            {"icon":[52],"temperature":{"data":[{"place":"Other","value":20},
            {"place":"Hong Kong Observatory","value":"29"}]},"humidity":{"data":[{"value":80}]},
            "warningMessage":["Rain",42],"updateTime":"2026-09-05T12:00:00+08:00"}
            """;

    @Test
    void normalizesAndCachesPerLanguageThenServesStaleAndRecovers() throws Exception {
        var client = mock(WeatherClient.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-09-05T04:00:00Z");
        when(clock.instant()).thenReturn(now);
        var json = new JsonMapper().readTree(READING);
        when(client.current("zh")).thenReturn(json).thenThrow(new IOException("upstream offline")).thenReturn(json);
        when(client.current("en")).thenReturn(json);
        var service = new WeatherService(client, clock);
        var first = service.load("zh");
        assertEquals(29, first.temperatureC());
        assertEquals(java.util.List.of("Rain"), first.warnings());
        assertSame(first, service.load("zh"));
        service.load("en");
        verify(client).current("en");
        when(clock.instant()).thenReturn(now.plusSeconds(601));
        var stale = service.load("zh");
        assertTrue(stale.stale());
        assertEquals(first.cachedAt(), stale.cachedAt());
        assertEquals("upstream offline", stale.error());
        assertSame(stale, service.load("zh"));
        when(clock.instant()).thenReturn(now.plusSeconds(662));
        assertFalse(service.load("zh").stale());
        verify(client, times(3)).current("zh");
    }

    @Test
    void rejectsInvalidReadingsAndPropagatesColdFailure() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WeatherService.normalize(new JsonMapper().readTree("{}"), Instant.EPOCH));
        var client = mock(WeatherClient.class);
        when(client.current("zh")).thenThrow(new IOException("timeout"));
        var service = new WeatherService(client, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> service.load("zh"));
        assertThrows(IllegalArgumentException.class, () -> service.load("fr"));
    }
}
