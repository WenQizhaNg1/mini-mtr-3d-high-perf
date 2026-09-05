package dev.minimtr.repo;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.model.entity.ParsedDataset;
import java.util.*;
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
            upsert(id, feature);
        }
    }
    public long lock(String code) {
        return db.fetchSingle("SELECT id FROM app.dataset WHERE code=? FOR UPDATE", code).get(0, Long.class);
    }
    public List<JsonNode> features(String code) {
        return db.fetch("""
                SELECT jsonb_build_object('type','Feature','id',f.source_key,'objectId',f.object_id::text,
                    'geometry',ST_AsGeoJSON(o.geom)::jsonb,'properties',f.properties)::text
                FROM app.feature f JOIN app.dataset d ON d.id=f.dataset_id
                JOIN app.spatial_object o ON o.id=f.object_id WHERE d.code=? ORDER BY f.source_key
                """, code).map(r -> json.readTree(r.get(0, String.class)));
    }
    public Optional<JsonNode> feature(String code,String key) {
        return db.fetchOptional("""
                SELECT jsonb_build_object('type','Feature','id',f.source_key,'objectId',f.object_id::text,
                    'geometry',ST_AsGeoJSON(o.geom)::jsonb,'properties',f.properties)::text
                FROM app.feature f JOIN app.dataset d ON d.id=f.dataset_id
                JOIN app.spatial_object o ON o.id=f.object_id WHERE d.code=? AND f.source_key=?
                """,code,key).map(r -> json.readTree(r.get(0,String.class)));
    }
    public void upsert(long dataset, ParsedDataset.Feature feature) {
        String key = feature.sourceKey() == null ? UUID.randomUUID().toString() : feature.sourceKey();
        var existing = db.fetchOptional("SELECT object_id FROM app.feature WHERE dataset_id=? AND source_key=?", dataset, key);
        if (existing.isPresent()) {
            long object = existing.get().get(0, Long.class);
            db.execute("UPDATE app.spatial_object SET geom=ST_GeomFromText(?,4326) WHERE id=?", feature.wkt(), object);
            db.execute("UPDATE app.feature SET properties=?::jsonb WHERE dataset_id=? AND source_key=?",
                    feature.properties().toString(), dataset, key);
        } else {
            db.execute("""
                    WITH object AS (
                        INSERT INTO app.spatial_object(geom) VALUES(ST_GeomFromText(?,4326)) RETURNING id
                    ) INSERT INTO app.feature(dataset_id,object_id,properties,source_key) SELECT ?,id,?::jsonb,? FROM object
                    """, feature.wkt(), dataset, feature.properties().toString(), key);
        }
    }
    public List<String> dependencies(long object) {
        return db.fetch("""
                SELECT 'route:' || o.code || ':' || r.code AS dependency FROM app.route r
                JOIN app.line l ON l.id=r.line_id JOIN app.operator o ON o.id=l.operator_id WHERE r.object_id=?
                UNION ALL SELECT 'station:' || o.code || ':' || s.code FROM app.station s
                JOIN app.operator o ON o.id=s.operator_id WHERE s.object_id=?
                UNION ALL SELECT 'stop:' || code FROM app.stop WHERE object_id=?
                """, object, object, object).getValues(0, String.class);
    }
    public void delete(long dataset, String key, long object) {
        db.execute("DELETE FROM app.feature WHERE dataset_id=? AND source_key=?", dataset, key);
        db.execute("DELETE FROM app.spatial_object WHERE id=? AND NOT EXISTS (SELECT 1 FROM app.feature WHERE object_id=?)",
                object, object);
    }
    public void refreshFields(long dataset) {
        var fields = new LinkedHashMap<String, String>();
        var rows = db.fetch("""
                SELECT p.key, array_agg(DISTINCT jsonb_typeof(p.value)) FILTER (WHERE p.value <> 'null'::jsonb) AS types
                FROM app.feature f CROSS JOIN LATERAL jsonb_each(f.properties) p WHERE f.dataset_id=? GROUP BY p.key
                """, dataset);
        for (var row : rows) {
            String key = row.get("key", String.class);
            var types = row.get("types", String[].class);
            dev.minimtr.service.DatasetParser.require(types == null || types.length == 1, "Mixed property types: " + key);
            fields.put(key, types == null ? "string" : types[0]);
        }
        db.execute("UPDATE app.dataset SET fields=?::jsonb WHERE id=?", json.writeValueAsString(fields), dataset);
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
