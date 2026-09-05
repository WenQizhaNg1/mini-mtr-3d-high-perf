package dev.minimtr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Opt-in: uses the configured local PostGIS database, with a random HTTP port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
class BackendIT {
    @LocalServerPort
    int port;

    @Autowired
    DSLContext db;

    @Autowired
    Flyway flyway;

    @Test
    void startsWithPostgisJooqFlywayAndHttp() throws Exception {
        assertEquals(1, db.selectOne().fetchSingle().value1());
        assertTrue(db.fetchOne("select exists (select 1 from pg_catalog.pg_extension where extname = 'postgis')").get(0, Boolean.class));
        assertEquals("5", flyway.info().current().getVersion().getVersion());
        assertEquals("backend_meta", flyway.getConfiguration().getDefaultSchema());

        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"ok\""));
            assertTrue(response.body().contains("\"database\":\"up\""));
        }
    }

    @Test
    void servesMigratedEndpointsAndRejectsInvalidTime() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (var path : new String[]{"/api/network", "/api/service-day?at=2026-09-05T00:00:00Z",
                    "/api/trains?at=2026-09-05T00:00:00Z"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            }
            var invalid = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/service-day?at=invalid")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.body().contains("Invalid at timestamp"));
            var live = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/trains")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, live.statusCode());
            var invalidTrain = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/trains?at=invalid")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidTrain.statusCode());
        }
    }

    @Test
    void publishesNewMapViewsAndAllowsFrontendRequests() throws Exception {
        assertEquals(db.fetchCount(org.jooq.impl.DSL.table("app.route")),
                db.fetchCount(org.jooq.impl.DSL.table("app.map_routes")));
        assertEquals(db.fetchCount(org.jooq.impl.DSL.table("app.station")),
                db.fetchCount(org.jooq.impl.DSL.table("app.map_stations")));
        assertEquals(0, db.fetchOne("SELECT count(*) FROM app.map_routes WHERE geom IS NULL OR from_code IS NULL OR to_code IS NULL").get(0, Integer.class));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/network"))
                    .header("Origin", "http://localhost:8080").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            var invalid = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/weather?lang=invalid"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
        }
    }
}
