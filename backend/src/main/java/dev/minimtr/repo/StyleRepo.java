package dev.minimtr.repo;

import dev.minimtr.model.entity.MapStyle;
import dev.minimtr.model.entity.MapLayer;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@Repository
public class StyleRepo {
    private final DSLContext db;
    private final JsonMapper json;

    public StyleRepo(DSLContext db, JsonMapper json) { this.db = db; this.json = json; }

    public List<MapStyle> list() {
        return db.fetch("SELECT id, code, name, basemap FROM app.style ORDER BY code")
                .map(r -> new MapStyle(r.get("id", Long.class), r.get("code", String.class),
                        r.get("name", String.class), r.get("basemap", String.class)));
    }

    public Optional<MapStyle> find(String code) {
        return db.fetchOptional("SELECT id, code, name, basemap FROM app.style WHERE code = ?", code)
                .map(r -> new MapStyle(r.get("id", Long.class), r.get("code", String.class),
                        r.get("name", String.class), r.get("basemap", String.class)));
    }

    public List<MapLayer> layers(long styleId) {
        return db.fetch("SELECT * FROM app.layer WHERE style_id = ? ORDER BY position", styleId)
                .map(r -> new MapLayer(r.get("code", String.class), r.get("source", String.class),
                        r.get("source_layer", String.class), r.get("type", String.class),
                        r.get("position", Integer.class), r.get("minzoom", Double.class), r.get("maxzoom", Double.class),
                        json.readTree(r.get("layout", JSONB.class).data()), json.readTree(r.get("paint", JSONB.class).data()),
                        r.get("filter", JSONB.class) == null ? null : json.readTree(r.get("filter", JSONB.class).data()),
                        r.get("enabled", Boolean.class),json.readTree(r.get("metadata",JSONB.class).data())));
    }

    public void save(String code, String name, String basemap, JsonNode layers) {
        save(code,name,basemap,layers,json.createObjectNode());
    }
    public void save(String code,String name,String basemap,JsonNode layers,JsonNode metadata) {
        long id = db.fetchSingle("""
                INSERT INTO app.style(code,name,basemap,metadata) VALUES(?,?,?,?::jsonb)
                ON CONFLICT(code) DO UPDATE SET name=excluded.name,basemap=excluded.basemap,metadata=excluded.metadata RETURNING id
                """, code, name, basemap,metadata.toString()).get(0, Long.class);
        db.execute("DELETE FROM app.layer WHERE style_id=?", id);
        int position = 0;
        for (var layer : layers) {
            db.execute("""
                    INSERT INTO app.layer(style_id,code,source,source_layer,type,position,minzoom,maxzoom,layout,paint,filter,metadata)
                    VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
                    """, id, layer.path("id").asText(), layer.path("source").asText(),
                    layer.has("source-layer") ? layer.get("source-layer").asText() : null,
                    layer.path("type").asText(), position++, layer.path("minzoom").asDouble(0), layer.path("maxzoom").asDouble(24),
                    layer.has("layout") ? layer.get("layout").toString() : "{}",
                    layer.has("paint") ? layer.get("paint").toString() : "{}",
                    layer.has("filter") ? layer.get("filter").toString() : null,
                    layer.has("metadata") ? layer.get("metadata").toString() : "{}");
        }
    }
    public JsonNode metadata(String code) {
        return json.readTree(db.fetchSingle("SELECT metadata FROM app.style WHERE code=?",code).get(0,JSONB.class).data());
    }
}
