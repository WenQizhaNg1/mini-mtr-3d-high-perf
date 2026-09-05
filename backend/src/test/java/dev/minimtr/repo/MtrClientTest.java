package dev.minimtr.repo;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class MtrClientTest {
    @Test
    void readsTheActualHttpContractAndReportsHttpFailures() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schedule", exchange -> {
            var body = "{\"status\":1,\"curr_time\":\"2026-09-05 08:00:00\",\"data\":{\"TWL-MOK\":{\"UP\":[{\"dest\":\"TSW\",\"time\":\"2026-09-05 08:02:00\",\"valid\":\"Y\"}]}}}".getBytes(StandardCharsets.UTF_8);
            assertTrue(exchange.getRequestURI().getQuery().contains("line=TWL"));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/failure", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var http = new dev.minimtr.config.HttpConfig().outboundHttpClient("");
        var client = new MtrClient(http, JsonMapper.builder().build(), base + "/schedule");
        var failure = new MtrClient(http, JsonMapper.builder().build(), base + "/failure");
        try {
            var response = client.schedule("TWL", "MOK");
            assertEquals(1, response.status());
            assertEquals("TSW", response.data().get("TWL-MOK").up().getFirst().dest());
            var error = assertThrows(java.io.IOException.class, () -> failure.schedule("TWL", "MOK"));
            assertTrue(error.getMessage().contains("503"));
        } finally { http.shutdownNow(); server.stop(0); }
    }
}
