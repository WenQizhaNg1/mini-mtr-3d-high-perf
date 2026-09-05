package dev.minimtr;

import dev.minimtr.model.dto.*;
import dev.minimtr.service.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"workbench.token=integration-only","schedule.enabled=false","live.enabled=false"})
class TransitIT {
    @Autowired DatasetService datasets;
    @Autowired TransitConfigService configs;
    @Autowired TransitFrames frames;
    @Autowired RealtimeFrames realtime;
    @Autowired DSLContext db;
    @Autowired JsonMapper json;

    String create(String suffix) {
        String code="test-"+suffix+"-"+UUID.randomUUID().toString().substring(0,8);
        datasets.create(new DatasetImport(code+"-paths","Paths","route","geojson","""
            {"type":"FeatureCollection","features":[{"type":"Feature","id":"path","properties":{"line":"L","colour":"#007dc5"},
              "geometry":{"type":"LineString","coordinates":[[114,22],[114.01,22],[114.02,22]]}}]}
            """,null,null,null,Map.of()));
        datasets.create(new DatasetImport(code+"-stations","Stations","station","geojson","""
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"A","properties":{"name":"A"},"geometry":{"type":"Point","coordinates":[114,22]}},
              {"type":"Feature","id":"B","properties":{"name":"B"},"geometry":{"type":"Point","coordinates":[114.01,22]}},
              {"type":"Feature","id":"C","properties":{"name":"C"},"geometry":{"type":"Point","coordinates":[114.02,22]}}]}
            """,null,null,null,Map.of()));
        return code;
    }
    TransitConfig config(String data,String mode) {
        return new TransitConfig("Test","Asia/Hong_Kong","feed",mode,
            new TransitConfig.Mapping(data+"-paths",data+"-stations",Map.of("line","line","colour","colour"),Map.of("name","name"),
                List.of(new TransitConfig.Pattern("out","path","out",false,List.of(new TransitConfig.Stop("A",0.0),
                    new TransitConfig.Stop("B",0.5),new TransitConfig.Stop("C",1.0)))),50),
            new TransitConfig.Simulation("00:00",List.of(),List.of(new TimetableTrip("T","out",null,List.of(
                new TimetableTrip.Call(0,null,"23:58"),new TimetableTrip.Call(1,"24:00","24:01"),new TimetableTrip.Call(2,"24:03",null))))),
            new TransitConfig.Realtime(List.of(),45));
    }
    @Test void simulationGeometryEditsIdentityIsolationAndEtaOnly() {
        String data=create("motion"),other=data+"-other";
        try {
            configs.save(data,config(data,"simulation"));
            configs.save(other,config(data,"simulation"));
            Instant midnight=Instant.parse("2026-09-05T16:00:30Z");
            var frame=frames.frame(data,"simulation",midnight);
            assertEquals(1,frame.trains().size());
            assertEquals("dwell",frame.trains().getFirst().state());
            assertEquals(114.01,frame.trains().getFirst().lng(),1e-6);
            assertNotEquals(frame.trains().getFirst().id(),frames.frame(other,"simulation",midnight).trains().getFirst().id());
            var before=datasets.features(data+"-paths");
            var sourceId=datasets.feature(data+"-paths","path").path("objectId").asText();
            assertThrows(ResponseStatusException.class,() -> datasets.deleteFeature(data+"-paths","path"));
            datasets.saveFeature(data+"-paths","path",json.readTree("""
                {"type":"Feature","properties":{"line":"L","colour":"#007dc5"},
                 "geometry":{"type":"LineString","coordinates":[[114,22],[114.01,22.002],[114.02,22]]}}
                """));
            assertEquals(sourceId,datasets.feature(data+"-paths","path").path("objectId").asText());
            assertEquals(22.002,frames.frame(data,"simulation",midnight).trains().getFirst().lat(),1e-5);
            assertEquals(22.002,frames.frame(other,"simulation",midnight).trains().getFirst().lat(),1e-5);
            var valid=datasets.feature(data+"-paths","path");
            assertThrows(ResponseStatusException.class,() -> datasets.saveFeature(data+"-paths","path",json.readTree("""
                {"type":"Feature","properties":{"line":"L","colour":"#007dc5"},
                 "geometry":{"type":"LineString","coordinates":[[114,22],[120,30],[130,40]]}}
                """)));
            assertEquals(valid,datasets.feature(data+"-paths","path"));
            assertEquals(22.002,frames.frame(data,"simulation",midnight).trains().getFirst().lat(),1e-5);
            var rt=frames.frame(data,"realtime",null);
            assertTrue(rt.trains().isEmpty()); assertTrue(rt.arrivals().isEmpty());
            Instant now=Instant.now();
            realtime.ingest(data,List.of(new RealtimeObservation("eta","arrival-1",null,"L",null,now,
                null,null,null,null,"B","C",now.plusSeconds(120))));
            rt=frames.frame(data,"realtime",null);
            assertTrue(rt.trains().isEmpty()); assertEquals(1,rt.arrivals().size());
            assertThrows(ResponseStatusException.class,() -> frames.frame(data,"realtime",midnight));
            realtime.ingest(data,List.of(new RealtimeObservation("gps","gps-1","vehicle","L",null,now.minusSeconds(60),
                114.009,22.005,90.0,null,null,null,null)));
            var gps=frames.frame(data,"realtime",null).trains().getFirst();
            assertEquals(22.005,gps.lat()); assertEquals("stale",gps.estimate());
            assertThrows(ResponseStatusException.class,() -> realtime.ingest(data,List.of(new RealtimeObservation("gps","gps-1","vehicle","L",null,now.minusSeconds(61),
                114.01,22.003,90.0,null,null,null,null))));
            assertEquals(114.01,frames.frame(data,"simulation",midnight).trains().getFirst().lng(),1e-5);
            assertTrue(db.fetchSingle("SELECT octet_length(app.transit_tile(0,0,0,?::json))","{\"operator\":\""+data+"\"}").get(0,Integer.class)>0);
            assertEquals(0,db.fetchSingle("SELECT octet_length(app.transit_tile(0,0,0,'{\"operator\":\"missing\"}'))").get(0,Integer.class));
        } finally {
            for(String code:List.of(other,data)) if(configs.list().stream().anyMatch(c -> c.code().equals(code))) configs.remove(code);
        }
    }
}
