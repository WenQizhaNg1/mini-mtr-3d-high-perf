package dev.minimtr.repo;

import dev.minimtr.model.entity.Line;
import dev.minimtr.model.entity.Station;
import java.util.Arrays;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class NetworkRepo {
    private final DSLContext db;

    public NetworkRepo(DSLContext db) {
        this.db = db;
    }

    public List<Line> lines() {
        return db.fetch("""
                SELECT l.code, l.name, l.name_en, l.colour
                FROM app.line l JOIN app.operator o ON o.id = l.operator_id
                WHERE o.code = 'mtr' ORDER BY l.id
                """).map(r -> new Line(r.get("code", String.class), r.get("name", String.class),
                        r.get("name_en", String.class), r.get("colour", String.class)));
    }

    public List<Station> stations() {
        return db.fetch("""
                SELECT s.code, s.name, s.name_en,
                    coalesce(array_agg(DISTINCT l.code ORDER BY l.code)
                        FILTER (WHERE l.code IS NOT NULL), ARRAY[]::text[]) AS line_ids
                FROM app.station s JOIN app.operator o ON o.id = s.operator_id
                LEFT JOIN app.line_station ls ON ls.station_id = s.id
                LEFT JOIN app.line l ON l.id = ls.line_id
                WHERE o.code = 'mtr'
                GROUP BY s.id ORDER BY s.code
                """).map(r -> new Station(r.get("code", String.class), r.get("name", String.class),
                        r.get("name_en", String.class), Arrays.asList(r.get("line_ids", String[].class))));
    }
}
