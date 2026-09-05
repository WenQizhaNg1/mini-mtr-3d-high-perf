package dev.minimtr.config;

import com.sun.net.httpserver.HttpServer;
import dev.minimtr.repo.MtrClient;
import dev.minimtr.repo.WeatherClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HttpConfigTest {
    @Test
    void emptyConfigurationUsesDirectAccess() {
        try (var client = new HttpConfig().outboundHttpClient("")) {
            assertEquals(List.of(Proxy.NO_PROXY), client.proxy().orElseThrow()
                    .select(URI.create("https://data.weather.gov.hk/")));
        }
    }

    @Test
    void rejectsUnsupportedOrIncompleteProxyUrls() {
        for (var url : List.of("socks5://localhost:1083", "https://localhost:1083", "http://localhost",
                "http://localhost:0", "http://localhost:65536", "http://user:secret@localhost:1083",
                "http://localhost:1083/path", "http://localhost:1083?x=1", "not a url")) {
            assertThrows(IllegalArgumentException.class, () -> new HttpConfig().outboundHttpClient(url));
        }
    }

    @Test
    void bothUpstreamClientsUseTheConfiguredProxy() throws Exception {
        var requests = new CopyOnWriteArrayList<URI>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI());
            var body = "{\"status\":1,\"updateTime\":\"2026-09-05T12:00:00+08:00\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        proxy.start();
        int port = proxy.getAddress().getPort();
        try (var http = new HttpConfig().outboundHttpClient("http://127.0.0.1:" + port)) {
            var httpsProxy = http.proxy().orElseThrow().select(URI.create("https://data.weather.gov.hk/"));
            assertEquals(Proxy.Type.HTTP, httpsProxy.getFirst().type());
            assertEquals(port, ((InetSocketAddress) httpsProxy.getFirst().address()).getPort());
            var mapper = JsonMapper.builder().build();
            // Reserved .invalid hosts can only succeed through our local proxy.
            assertEquals(1, new MtrClient(http, mapper, "http://mtr.invalid/schedule").schedule("TWL", "MOK").status());
            var weather = new WeatherClient(http, mapper, "http://weather.invalid/weather");
            assertTrue(weather.current("zh").has("updateTime"));
            weather.current("en");
            assertEquals(3, requests.size());
            assertEquals("mtr.invalid", requests.get(0).getHost());
            assertEquals("weather.invalid", requests.get(1).getHost());
            assertTrue(requests.get(1).getQuery().contains("lang=tc"));
            assertTrue(requests.get(2).getQuery().contains("lang=en"));
        } finally { proxy.stop(0); }
    }
}
