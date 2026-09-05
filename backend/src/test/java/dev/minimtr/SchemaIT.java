package dev.minimtr;

import org.jooq.DSLContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"schedule.enabled=false", "live.enabled=false", "realtime.enabled=false"})
@Transactional
class SchemaIT {
    @Autowired DSLContext db;

    @Test
    void rejectsWrongSrid() {
        assertThrows(DataIntegrityViolationException.class, () -> db.execute("""
                INSERT INTO app.spatial_object (geom) VALUES (ST_SetSRID(ST_MakePoint(1, 2), 3857))
                """));
    }

    @Test
    void rejectsEmptyGeometry() {
        assertThrows(DataIntegrityViolationException.class, () -> db.execute("""
                INSERT INTO app.spatial_object (geom) VALUES (ST_GeomFromText('POINT EMPTY', 4326))
                """));
    }

    @Test
    void acceptsWgs84PolygonWithoutCouplingToOntology() {
        assertEquals(1, db.execute("""
                INSERT INTO app.spatial_object (geom)
                VALUES (ST_GeomFromText('POLYGON((114 22,114.1 22,114.1 22.1,114 22))', 4326))
                """));
        assertEquals(0, db.fetchOne("""
                SELECT count(*)::int FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'app.ontology'::regclass
                """).get(0, Integer.class));
    }
}
