package dev.minimtr;

import dev.minimtr.service.LegacyImportService;
import dev.minimtr.service.NetworkService;
import java.util.Arrays;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

// Read-only comparison of the explicitly imported local snapshot. Not required on fresh databases.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
@EnabledIfSystemProperty(named = "legacy.verify", matches = "true")
class LegacySnapshotIT {
    @Autowired DSLContext db;
    @Autowired NetworkService network;
    @Autowired LegacyImportService importer;

    @Test
    void stationCatalogPreservesLegacyMembershipAndInterchanges() {
        var stations = network.load().stations();
        var old = db.fetch("SELECT code, name_en, name_zh, interchange, line_ids FROM mtr.stations ORDER BY code");
        assertEquals(old.size(), stations.size());
        for (int i = 0; i < old.size(); i++) {
            var source = old.get(i);
            var station = stations.get(i);
            assertEquals(source.get("code"), station.code());
            assertEquals(source.get("name_en"), station.nameEn());
            assertEquals(source.get("name_zh"), station.nameZh());
            assertEquals(source.get("interchange"), station.interchange(), station.code());
            assertEquals(Arrays.asList(source.get("line_ids", String.class).split(",")), station.lineIds(), station.code());
        }
    }

    @Test
    void allLegacyLegTimesAndRouteGeometryArePreserved() {
        assertEquals(0, db.fetchOne("""
                SELECT count(*)::int FROM mtr.train_legs old
                LEFT JOIN app.trip t ON t.source = 'legacy-mtr' AND t.source_key = old.train_id
                LEFT JOIN app.trip_stop a ON a.trip_id = t.id AND a.seq = old.from_stop_sequence
                LEFT JOIN app.trip_stop b ON b.trip_id = t.id AND b.seq = old.to_stop_sequence
                WHERE a.departure_at IS DISTINCT FROM old.departure_at
                    OR b.arrival_at IS DISTINCT FROM old.arrival_at
                """).get(0, Integer.class));
        assertEquals(0, db.fetchOne("""
                SELECT count(*)::int FROM mtr.route_patterns old
                LEFT JOIN app.route r ON r.code = old.id
                LEFT JOIN app.spatial_object o ON o.id = r.object_id
                WHERE o.id IS NULL OR NOT ST_OrderingEquals(old.geom, o.geom)
                """).get(0, Integer.class));
    }

    @Test
    void repeatedImportRefusesToOverwrite() {
        int before = db.fetchOne("SELECT count(*)::int FROM app.trip").get(0, Integer.class);
        assertThrows(IllegalStateException.class, () -> importer.importSnapshot());
        assertEquals(before, db.fetchOne("SELECT count(*)::int FROM app.trip").get(0, Integer.class));
    }
}
