package dev.minimtr.repo;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.model.entity.ParsedDataset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DatasetRepo {
    private final DSLContext db;
    private final JsonMapper json;
    public DatasetRepo(DSLContext db, JsonMapper json) { this.db = db; this.json = json; }

    public List<JsonNode> list(boolean drafts) {
        return db.fetch("SELECT row_to_json(d)::text FROM app.dataset d WHERE ? OR published ORDER BY id", drafts)
                .map(r -> json.readTree(r.get(0, String.class)));
    }
    public JsonNode find(String code, boolean drafts) {
        return db.fetchOptional("SELECT row_to_json(d)::text FROM app.dataset d WHERE code=? AND (? OR published)", code, drafts)
                .map(r -> json.readTree(r.get(0, String.class))).orElse(null);
    }
    public List<JsonNode> ontology() {
        return db.fetch("SELECT row_to_json(o)::text FROM app.ontology o ORDER BY code")
                .map(r -> json.readTree(r.get(0, String.class)));
    }
    public void insert(DatasetImport input, ParsedDataset parsed) {
        long id = db.fetchSingle("INSERT INTO app.dataset(code,name,ontology_code,fields) VALUES (?,?,?,?::jsonb) RETURNING id",
                input.code(), input.name(), input.ontology(), json.writeValueAsString(parsed.fields())).get(0, Long.class);
        for (var feature : parsed.features()) {
            db.execute("""
                    WITH object AS (
                        INSERT INTO app.spatial_object(geom) VALUES(ST_GeomFromText(?,4326)) RETURNING id
                    ) INSERT INTO app.feature(dataset_id,object_id,properties) SELECT ?,id,?::jsonb FROM object
                    """, feature.wkt(), id, feature.properties().toString());
        }
    }
    public List<String> geometryTypes(String code) {
        return db.fetch("""
                SELECT DISTINCT GeometryType(o.geom) FROM app.dataset d
                JOIN app.feature f ON f.dataset_id=d.id JOIN app.spatial_object o ON o.id=f.object_id WHERE d.code=?
                """, code).map(r -> r.get(0, String.class));
    }
    public void publish(String code, boolean published) {
        db.execute("UPDATE app.dataset SET published=? WHERE code=?", published, code);
    }
    public List<Double> bounds(String code) {
        var row = db.fetchSingle("""
                SELECT ST_XMin(extent), ST_YMin(extent), ST_XMax(extent), ST_YMax(extent) FROM (
                    SELECT ST_Extent(o.geom) AS extent FROM app.dataset d
                    JOIN app.feature f ON f.dataset_id=d.id JOIN app.spatial_object o ON o.id=f.object_id WHERE d.code=?
                ) b
                """, code);
        if (row.get(0) == null) return List.of();
        return List.of(row.get(0,Double.class), row.get(1,Double.class), row.get(2,Double.class), row.get(3,Double.class));
    }
}
