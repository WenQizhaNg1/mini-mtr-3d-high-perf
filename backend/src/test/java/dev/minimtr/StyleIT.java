package dev.minimtr;

import dev.minimtr.service.StyleService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
class StyleIT {
    @LocalServerPort int port;
    @Autowired StyleService styles;
    @Autowired DSLContext db;
    @Autowired JsonMapper json;

    @Test
    void servesPresetsAndNativeLayerObjects() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var list = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/styles"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("mtr-light"));
            assertTrue(list.body().contains("mtr-dark"));
            for (var code : List.of("mtr-light", "mtr-dark")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/styles/" + code))
                        .header("Origin", "http://localhost:8080").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
                var style = json.readTree(response.body());
                assertEquals(code, style.path("code").asText());
                assertEquals(5, style.path("layers").size());
                var routes = style.path("layers").path(1);
                assertEquals("mtr-routes", routes.path("id").asText());
                assertEquals("mtr_routes", routes.path("source-layer").asText());
                assertTrue(routes.path("paint").path("line-width").isArray());
                assertEquals("case", style.path("layers").path(3).path("paint").path("fill-extrusion-color").path(0).asText());
            }
            var missing = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/styles/missing"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        }
    }

    @Test
    @Transactional
    void databaseChangesAreAuthoritativeAndReadsDoNotAlterStoredLayout() {
        var before = styles.load("mtr-light").orElseThrow();
        assertEquals("positron", before.basemap());
        long id = db.fetchSingle("SELECT id FROM app.style WHERE code = 'mtr-light'").get(0, Long.class);
        db.execute("""
                UPDATE app.layer SET paint = '{"line-color":"#123456","line-width":23}'::jsonb,
                    filter = '["==",["get","line_id"],"ISL"]'::jsonb,
                    minzoom = 12, maxzoom = 19, enabled = false, position = 100
                WHERE style_id = ? AND code = 'mtr-routes'
                """, id);
        var changed = styles.load("mtr-light").orElseThrow();
        var layer = changed.layers().getLast();
        assertEquals("mtr-routes", layer.path("id").asText());
        assertEquals("#123456", layer.path("paint").path("line-color").asText());
        assertEquals(23, layer.path("paint").path("line-width").asInt());
        assertEquals(12, layer.path("minzoom").asInt());
        assertEquals(19, layer.path("maxzoom").asInt());
        assertTrue(layer.path("filter").isArray());
        assertEquals("none", layer.path("layout").path("visibility").asText());
        assertFalse(db.fetchSingle("SELECT layout::text FROM app.layer WHERE style_id = ? AND code = 'mtr-routes'", id)
                .get(0, String.class).contains("visibility"));
        assertEquals("#283341", styles.load("mtr-dark").orElseThrow().layers().getFirst().path("paint").path("line-color").asText());
        assertTrue(styles.load("missing").isEmpty());
    }
}
