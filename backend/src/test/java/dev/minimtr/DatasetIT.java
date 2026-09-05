package dev.minimtr;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.service.DatasetService;
import dev.minimtr.service.MapStyleService;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false", "workbench.token=integration-only"})
class DatasetIT {
    @Autowired DatasetService datasets;
    @Autowired MapStyleService maps;
    @Autowired DSLContext db;
    @Autowired JsonMapper json;
    @LocalServerPort int port;

    private DatasetImport input(String code) {
        return new DatasetImport(code, "Test", "station", "csv", "wkt,name\nPOINT (114 22),Test", "wkt", null, null, Map.of());
    }
    private byte[] tile(String code) {
        return db.fetchSingle("SELECT app.dataset_tile(0,0,0,?::json)", "{\"dataset\":\"" + code + "\"}").get(0, byte[].class);
    }

    @Test @Transactional
    void importsAndPublishesIsolatedTilesAndNativeStyle() {
        var draft = datasets.create(input("integration-stations"));
        assertFalse(draft.path("published").asBoolean());
        assertEquals(0, tile("integration-stations").length);
        assertThrows(ResponseStatusException.class, () -> datasets.find("integration-stations", false));
        datasets.publish("integration-stations", true);
        assertTrue(tile("integration-stations").length > 0);
        assertEquals(0, tile("another-dataset").length);
        assertEquals(0, db.fetchSingle("SELECT app.dataset_tile(30,0,0,'{}')").get(0, byte[].class).length);
        var style = (ObjectNode) json.readTree("""
                {"name":"Test style","basemap":"positron","layers":[{
                  "id":"custom-stations","type":"circle","source":"dataset:integration-stations","source-layer":"features",
                  "paint":{"circle-radius":12,"circle-color":"#ff0000"}
                }]}
                """);
        maps.save("integration-style", style);
        var full = maps.load("integration-style");
        assertEquals(8, full.path("version").asInt());
        assertTrue(full.path("sources").path("dataset:integration-stations").path("tiles").path(0).asText().contains("?dataset=integration-stations"));
        assertEquals("custom-stations", full.withArray("layers").get(full.withArray("layers").size()-1).path("id").asText());
        assertTrue(full.path("glyphs").asText().startsWith("http://127.0.0.1:8081/"));
        datasets.publish("integration-stations", false);
        assertEquals(0, tile("integration-stations").length);
        assertFalse(maps.load("integration-style").path("sources").has("dataset:integration-stations"));
        assertThrows(ResponseStatusException.class, () -> maps.save("integration-style", style));
    }

    @Test @Transactional
    void rejectsOntologyBeforeWritingAnything() {
        int before = db.fetchSingle("SELECT count(*) FROM app.spatial_object").get(0, Integer.class);
        var bad = new DatasetImport("integration-bad", "Bad", "route", "wkt", "POINT (114 22)", null,null,null,Map.of());
        assertThrows(ResponseStatusException.class, () -> datasets.create(bad));
        assertEquals(before, db.fetchSingle("SELECT count(*) FROM app.spatial_object").get(0, Integer.class));
    }

    @Test void protectsWritesAndServesCompleteExistingStyles() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + port;
            var preview = HttpRequest.newBuilder(URI.create(base + "/api/datasets/preview"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input("preview-only"))));
            assertEquals(401, client.send(preview.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            for (String path : new String[]{"/api/datasets", "/api/styles/test", "/api/datasets/test/publication"}) {
                var denied = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                        .method(path.equals("/api/datasets") ? "POST" : "PUT", HttpRequest.BodyPublishers.ofString("not-json"));
                // Authentication must precede JSON parsing.
                assertEquals(401, client.send(denied.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/admin/datasets")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var valid = client.send(preview.header("Authorization", "Bearer integration-only").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valid.statusCode(), valid.body());
            assertEquals(1, json.readTree(valid.body()).path("count").asInt());
            for (String code : new String[]{"mtr-light", "mtr-dark"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/styles/" + code + "/style.json")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                var full = json.readTree(response.body());
                assertTrue(full.path("sources").has("mtr"));
                assertTrue(full.path("sources").has("mtr-trains"));
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            }
        }
    }
}
