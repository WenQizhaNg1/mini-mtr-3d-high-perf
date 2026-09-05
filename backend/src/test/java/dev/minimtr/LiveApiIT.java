package dev.minimtr;

import dev.minimtr.model.vo.TrainSnapshotVo;
import dev.minimtr.model.vo.TrainVo;
import dev.minimtr.service.LiveTrainService;
import dev.minimtr.service.MotionService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"schedule.enabled=false", "live.enabled=true", "realtime.enabled=false"})
@DirtiesContext
class LiveApiIT {
    @LocalServerPort int port;
    @Autowired LiveTrainService live;

    @TestConfiguration
    static class Fixtures {
        @Bean @Primary
        MotionService fixtureMotion() throws Exception {
            var motion = mock(MotionService.class);
            var ticks = new AtomicLong(1_788_566_400_000L);
            when(motion.snapshot()).thenAnswer(call -> {
                long at = ticks.getAndAdd(1000);
                return new TrainSnapshotVo(Instant.ofEpochMilli(at), List.of(new TrainVo("one", "TWL", "test", "#123456",
                        114, 22, 0, "running", "A", "B", "B", 0, Instant.ofEpochMilli(at), Instant.ofEpochMilli(at + 10000),
                        "observed", List.of(new TrainVo.MotionPoint(at, 114, 22, 0), new TrainVo.MotionPoint(at + 3000, 114, 22.001, 0)))));
            });
            return motion;
        }
    }

    @Test
    void exposesLiveSnapshotStreamsUpdatesAndStopsCleanly() throws Exception {
        String base = "http://127.0.0.1:" + port;
        try (var client = HttpClient.newHttpClient()) {
            var latest = client.send(HttpRequest.newBuilder(URI.create(base + "/api/trains")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, latest.statusCode());
            assertTrue(latest.body().contains("\"estimate\":\"observed\""));
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/trains/live"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/event-stream"));
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try (var body = response.body()) {
                var future = executor.submit(() -> {
                    var reader = new BufferedReader(new InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8));
                    var data = new java.util.ArrayList<String>();
                    boolean retry = false;
                    String line;
                    while (data.size() < 2 && (line = reader.readLine()) != null) {
                        if (line.equals("retry:3000") || line.equals("retry: 3000")) retry = true;
                        if (line.startsWith("data:")) data.add(line);
                    }
                    assertTrue(retry);
                    return data;
                });
                var data = future.get(8, TimeUnit.SECONDS);
                assertEquals(2, data.size());
                assertNotEquals(data.getFirst(), data.getLast());
                assertTrue(data.getFirst().contains("\"motion\""));
            } finally { executor.shutdownNow(); }
            live.stop();
            assertFalse(live.isRunning());
            var stopped = client.send(HttpRequest.newBuilder(URI.create(base + "/api/trains")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, stopped.statusCode());
        }
    }
}
