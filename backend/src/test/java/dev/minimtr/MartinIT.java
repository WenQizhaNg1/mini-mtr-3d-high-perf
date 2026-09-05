package dev.minimtr;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.service.DatasetService;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Martin check: -Dmartin.test.url=http://127.0.0.1:18081. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
@EnabledIfSystemProperty(named="martin.test.url", matches=".+")
class MartinIT {
    @Autowired DatasetService datasets;
    @Autowired DSLContext db;
    @Autowired PlatformTransactionManager transactions;

    @Test void martinQueriesPublishedDatasetsWithoutCachingOldPublicationState() throws Exception {
        String code = "test-" + UUID.randomUUID();
        Long id = null;
        try (var client = HttpClient.newHttpClient()) {
            id = datasets.create(new DatasetImport(code,"Martin integration","station","wkt",
                    "POINT (114 22)",null,null,null,Map.of())).path("id").asLong();
            String url = System.getProperty("martin.test.url") + "/features/0/0/0?dataset=" + code;
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            assertEquals(204, client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
            datasets.publish(code, true);
            var published = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, published.statusCode());
            byte[] expected = db.fetchSingle("SELECT app.dataset_tile(0,0,0,?::json)", "{\"dataset\":\"" + code + "\"}").get(0,byte[].class);
            assertTrue(expected.length > 0);
            assertArrayEquals(expected, published.body());
            assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(url + "-missing")).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
            datasets.publish(code, false);
            assertEquals(204, client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        } finally {
            if (id != null) {
                long datasetId = id;
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    var objects = db.fetch("DELETE FROM app.feature WHERE dataset_id=? RETURNING object_id", datasetId)
                            .map(r -> r.get(0,Long.class));
                    for (long object : objects) db.execute("DELETE FROM app.spatial_object WHERE id=?", object);
                    db.execute("DELETE FROM app.dataset WHERE id=?", datasetId);
                });
            }
        }
    }
}
