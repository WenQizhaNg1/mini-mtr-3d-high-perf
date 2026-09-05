package dev.minimtr.repo;

import dev.minimtr.model.dto.TransitConfig;
import dev.minimtr.model.entity.TransitNetwork;
import dev.minimtr.service.RoutePath;
import dev.minimtr.service.TimetableCompiler;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TransitRepo {
    public record Configured(String code, long id, TransitConfig config) {}
    private final DSLContext db;
    private final JsonMapper json;
    public TransitRepo(DSLContext db, JsonMapper json) { this.db = db; this.json = json; }
    public List<Configured> configs() {
        return db.fetch("""
                SELECT o.id,o.code,o.name,o.timezone,c.adapter,c.mode,c.mapping::text,c.simulation_params::text,c.realtime_params::text
                FROM app.operator o JOIN app.transit_config c ON c.operator_id=o.id ORDER BY o.code
                """).map(r -> new Configured(r.get("code",String.class), r.get("id",Long.class),
                    new TransitConfig(r.get("name",String.class), r.get("timezone",String.class), r.get("adapter",String.class),
                        r.get("mode",String.class), json.readValue(r.get("mapping",String.class),TransitConfig.Mapping.class),
                        json.readValue(r.get("simulation_params",String.class),TransitConfig.Simulation.class),
                        json.readValue(r.get("realtime_params",String.class),TransitConfig.Realtime.class))));
    }
    public long save(String code, TransitConfig config) {
        long id = db.fetchSingle("""
                INSERT INTO app.operator(code,name,timezone) VALUES (?,?,?)
                ON CONFLICT(code) DO UPDATE SET name=excluded.name,timezone=excluded.timezone RETURNING id
                """, code, config.name(), config.timezone()).get(0,Long.class);
        db.execute("""
                INSERT INTO app.transit_config(operator_id,adapter,mode,mapping,simulation_params,realtime_params)
                VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT(operator_id) DO UPDATE SET
                adapter=excluded.adapter,mode=excluded.mode,mapping=excluded.mapping,
                simulation_params=excluded.simulation_params,realtime_params=excluded.realtime_params
                """,id,config.adapter(),config.mode(),json.writeValueAsString(config.mapping()),
                json.writeValueAsString(config.simulation()),json.writeValueAsString(config.realtime()));
        return id;
    }
    public void lock(long operator) { db.fetchSingle("SELECT id FROM app.operator WHERE id=? FOR UPDATE",operator); }
    public List<LocalDate> days(long operator) {
        return db.fetch("""
                SELECT DISTINCT t.service_date FROM app.trip t JOIN app.route r ON r.id=t.route_id
                JOIN app.line l ON l.id=r.line_id WHERE l.operator_id=?
                """,operator).getValues(0,LocalDate.class);
    }
    public void clearPlans(long operator) {
        db.execute("""
                DELETE FROM app.trip_stop WHERE trip_id IN (SELECT t.id FROM app.trip t
                JOIN app.route r ON r.id=t.route_id JOIN app.line l ON l.id=r.line_id WHERE l.operator_id=?)
                """,operator);
        db.execute("""
                DELETE FROM app.trip WHERE route_id IN (SELECT r.id FROM app.route r
                JOIN app.line l ON l.id=r.line_id WHERE l.operator_id=?)
                """,operator);
    }
    public void clearBindings(long operator) {
        clearPlans(operator);
        db.execute("DELETE FROM app.route_stop WHERE route_id IN (SELECT r.id FROM app.route r JOIN app.line l ON l.id=r.line_id WHERE l.operator_id=?)",operator);
        db.execute("DELETE FROM app.line_station WHERE line_id IN (SELECT id FROM app.line WHERE operator_id=?)",operator);
    }
    public long line(long operator,String code,String name,String colour) {
        return db.fetchSingle("""
                INSERT INTO app.line(operator_id,code,name,name_en,colour,mode) VALUES (?,?,?,?,?,'rail')
                ON CONFLICT(operator_id,code) DO UPDATE SET name=excluded.name,name_en=excluded.name_en,colour=excluded.colour RETURNING id
                """,operator,code,name,name,colour).get(0,Long.class);
    }
    public long station(long operator,long object,String code,String name,String nameEn) {
        return db.fetchSingle("""
                INSERT INTO app.station(operator_id,object_id,code,name,name_en) VALUES (?,?,?,?,?)
                ON CONFLICT(operator_id,code) DO UPDATE SET object_id=excluded.object_id,name=excluded.name,name_en=excluded.name_en RETURNING id
                """,operator,object,code,name,nameEn).get(0,Long.class);
    }
    public long route(long object,long line,TransitConfig.Pattern pattern) {
        return db.fetchSingle("""
                INSERT INTO app.route(object_id,line_id,code,name,direction,reversed) VALUES (?,?,?,?,?,?)
                ON CONFLICT(line_id,code) DO UPDATE SET object_id=excluded.object_id,direction=excluded.direction,reversed=excluded.reversed RETURNING id
                """,object,line,pattern.code(),pattern.code(),pattern.direction(),pattern.reversed()).get(0,Long.class);
    }
    public void stop(long route,long station,long line,int seq,RoutePath.Projection projection) {
        db.execute("INSERT INTO app.route_stop(route_id,station_id,seq,distance_m,fraction) VALUES (?,?,?,?,?)",
                route,station,seq,projection.distanceMeters(),projection.fraction());
        membership(line, station);
    }
    public void membership(long line,long station) {
        db.execute("INSERT INTO app.line_station(line_id,station_id) VALUES (?,?) ON CONFLICT DO NOTHING",line,station);
    }
    public void prune(long operator,Collection<Long> routes,Collection<Long> stations,Collection<Long> lines) {
        db.execute("DELETE FROM app.route WHERE line_id IN (SELECT id FROM app.line WHERE operator_id=?) AND id<>ALL(?::bigint[])",
                operator,routes.toArray(Long[]::new));
        db.execute("DELETE FROM app.stop WHERE station_id IN (SELECT id FROM app.station WHERE operator_id=? AND id<>ALL(?::bigint[]))",
                operator,stations.toArray(Long[]::new));
        db.execute("DELETE FROM app.station WHERE operator_id=? AND id<>ALL(?::bigint[])",operator,stations.toArray(Long[]::new));
        db.execute("DELETE FROM app.line WHERE operator_id=? AND id<>ALL(?::bigint[])",operator,lines.toArray(Long[]::new));
    }
    public TransitNetwork network(long operator) {
        var stops = new HashMap<Long,List<TransitNetwork.Stop>>();
        db.fetch("""
                SELECT rs.route_id,rs.seq,s.code,rs.distance_m FROM app.route_stop rs JOIN app.station s ON s.id=rs.station_id
                JOIN app.route r ON r.id=rs.route_id JOIN app.line l ON l.id=r.line_id WHERE l.operator_id=? ORDER BY r.id,rs.seq
                """,operator).forEach(r -> stops.computeIfAbsent(r.get("route_id",Long.class),k -> new ArrayList<>())
                    .add(new TransitNetwork.Stop(r.get("seq",Integer.class),r.get("code",String.class),r.get("distance_m",Double.class))));
        var paths = new LinkedHashMap<String,TransitNetwork.Path>();
        db.fetch("""
                SELECT r.id,r.code,r.direction,l.code AS line_id,l.colour,
                ST_AsBinary(CASE WHEN r.reversed THEN ST_Reverse(o.geom) ELSE o.geom END) AS wkb
                FROM app.route r JOIN app.line l ON l.id=r.line_id JOIN app.spatial_object o ON o.id=r.object_id
                WHERE l.operator_id=? ORDER BY r.id
                """,operator).forEach(r -> {
                    long id = r.get("id",Long.class);
                    String code = r.get("code",String.class);
                    paths.put(code,new TransitNetwork.Path(id,code,r.get("line_id",String.class),r.get("colour",String.class),
                            r.get("direction",String.class),new RoutePath(r.get("wkb",byte[].class)),List.copyOf(stops.getOrDefault(id,List.of()))));
                });
        return new TransitNetwork(Map.copyOf(paths));
    }
    public boolean hasDay(long operator, LocalDate day) {
        return db.fetchSingle("""
                SELECT EXISTS(SELECT 1 FROM app.trip t JOIN app.route r ON r.id=t.route_id JOIN app.line l ON l.id=r.line_id
                WHERE l.operator_id=? AND t.service_date=?)
                """,operator,day).get(0,Boolean.class);
    }
    public List<dev.minimtr.model.entity.PlannedRun> runs(String operator,long operatorId,LocalDate first,LocalDate last) {
        var paths=new LinkedHashMap<String,String>();
        var nodes=new LinkedHashMap<String,List<dev.minimtr.model.entity.MotionNode>>();
        db.fetch("""
                SELECT t.service_date,t.source_key,r.code,s.code AS station,rs.distance_m,ts.arrival_at,ts.departure_at
                FROM app.trip t JOIN app.route r ON r.id=t.route_id JOIN app.line l ON l.id=r.line_id
                JOIN app.trip_stop ts ON ts.trip_id=t.id
                JOIN app.route_stop rs ON rs.route_id=ts.route_id AND rs.seq=ts.seq
                JOIN app.station s ON s.id=rs.station_id
                WHERE l.operator_id=? AND t.service_date BETWEEN ? AND ? ORDER BY t.id,ts.seq
                """,operatorId,first,last).forEach(r -> {
                    String id=operator+":"+r.get("service_date",LocalDate.class)+":"+r.get("source_key",String.class);
                    paths.put(id,r.get("code",String.class));
                    var values=nodes.computeIfAbsent(id,k -> new ArrayList<>());
                    for(String column:List.of("arrival_at","departure_at")) {
                        var time=r.get(column,OffsetDateTime.class);
                        if(time==null) continue;
                        var node=new dev.minimtr.model.entity.MotionNode(time.toInstant().toEpochMilli(),
                                r.get("distance_m",Double.class),r.get("station",String.class));
                        if(values.isEmpty() || !values.getLast().equals(node)) values.add(node);
                    }
                });
        return nodes.entrySet().stream().map(e -> new dev.minimtr.model.entity.PlannedRun(e.getKey(),paths.get(e.getKey()),e.getValue())).toList();
    }
    public Map<String,Object> catalog(Configured configured) {
        var c=configured.config();
        var lines=db.fetch("SELECT code,name,name_en,colour FROM app.line WHERE operator_id=? ORDER BY id",configured.id())
                .map(r -> Map.of("id",r.get("code",String.class),"apiCode",r.get("code",String.class),
                        "nameZh",r.get("name",String.class),"nameEn",Objects.requireNonNullElse(r.get("name_en",String.class),r.get("name",String.class)),
                        "colour",r.get("colour",String.class),"incidentAnchorStation",""));
        var stations=db.fetch("""
                SELECT s.code,s.name,s.name_en,coalesce(array_agg(l.code ORDER BY l.code) FILTER (WHERE l.code IS NOT NULL),ARRAY[]::text[]) AS lines
                FROM app.station s LEFT JOIN app.line_station ls ON ls.station_id=s.id LEFT JOIN app.line l ON l.id=ls.line_id
                WHERE s.operator_id=? GROUP BY s.id ORDER BY s.code
                """,configured.id()).map(r -> {
                    var memberships=Arrays.asList(r.get("lines",String[].class));
                    return Map.of("code",r.get("code",String.class),"nameZh",r.get("name",String.class),
                            "nameEn",Objects.requireNonNullElse(r.get("name_en",String.class),r.get("name",String.class)),
                            "lineIds",memberships,"interchange",memberships.size()>1);
                });
        var bounds=db.fetchSingle("""
                SELECT ST_XMin(b),ST_YMin(b),ST_XMax(b),ST_YMax(b) FROM (
                    SELECT ST_Extent(g.geom) b FROM app.route r JOIN app.line l ON l.id=r.line_id
                    JOIN app.spatial_object g ON g.id=r.object_id WHERE l.operator_id=?
                ) q
                """,configured.id());
        return Map.of("operator",configured.code(),"name",c.name(),"timezone",c.timezone(),"mode",c.mode(),
                "service",Map.of("serviceDayStart",c.simulation().serviceDayStart(),"serviceEndOffsetMinutes",1440),
                "lines",lines,"stations",stations,"bounds",bounds.get(0)==null?List.of():List.of(bounds.get(0,Double.class),
                        bounds.get(1,Double.class),bounds.get(2,Double.class),bounds.get(3,Double.class)));
    }
    public void insertPlans(String operator,LocalDate day,List<TimetableCompiler.Trip> plans) {
        String input=json.writeValueAsString(plans);
        db.execute("""
                INSERT INTO app.trip(route_id,service_date,code,source,source_key,plan_type)
                SELECT (p->>'routeId')::bigint,?::date,p->>'key',?,p->>'key',p->>'type'
                FROM jsonb_array_elements(?::jsonb) p
                """,day,operator+":schedule",input);
        db.execute("""
                WITH input AS MATERIALIZED (
                    SELECT * FROM jsonb_to_recordset(?::jsonb) AS p(key text,"routeId" bigint,stops jsonb)
                )
                INSERT INTO app.trip_stop(trip_id,route_id,seq,arrival_at,departure_at)
                SELECT t.id,t.route_id,s.seq,s."arrivalAt",s."departureAt"
                FROM input p JOIN app.trip t ON t.source=? AND t.service_date=? AND t.source_key=p.key
                CROSS JOIN LATERAL jsonb_to_recordset(p.stops) AS s(seq int,"arrivalAt" timestamptz,"departureAt" timestamptz)
                """,input,operator+":schedule",day);
    }
    public void remove(long operator) {
        clearBindings(operator);
        prune(operator,List.of(),List.of(),List.of());
        db.execute("DELETE FROM app.transit_config WHERE operator_id=?",operator);
        db.execute("DELETE FROM app.operator WHERE id=?",operator);
    }
    private OffsetDateTime offset(Instant time) { return time == null ? null : time.atOffset(ZoneOffset.UTC); }
}
