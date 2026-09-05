package dev.minimtr.service;

import dev.minimtr.model.dto.DatasetImport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class DatasetParserTest {
    private final DatasetParser parser = new DatasetParser(JsonMapper.builder().build());
    private DatasetImport input(String format, String content, Map<String,String> mapping) {
        return new DatasetImport("test", "Test", "station", format, content, "wkt", null, null, mapping);
    }
    @Test void readsGeojsonAndMapsScalarFields() {
        var parsed = parser.parse(input("geojson", """
                {"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[114,22]},
                "properties":{"站名":"九龙","number":7,"active":true}}]}
                """, Map.of("站名", "name", "number", "count")));
        assertEquals("POINT (114 22)", parsed.features().getFirst().wkt());
        assertEquals("九龙", parsed.features().getFirst().properties().path("name").asText());
        assertEquals(Map.of("name","string","count","number"), parsed.fields());
    }
    @Test void readsQuotedCsvAndKeepsLeadingZeros() {
        var parsed = parser.parse(input("csv", "wkt,code,name\r\n\"POINT (114 22)\",001,\"Station, East\"\r\n", Map.of()));
        assertEquals("001", parsed.features().getFirst().properties().path("code").asText());
        assertEquals("Station, East", parsed.features().getFirst().properties().path("name").asText());
        var coordinates = new DatasetImport("test", "Test", "station", "csv", "x,y\n114,22", null, "x", "y", Map.of());
        assertEquals("POINT (114 22)", parser.parse(coordinates).features().getFirst().wkt());
    }
    @Test void readsWktAndRejectsInvalidOrNonWgs84Geometry() {
        assertEquals(2, parser.parse(input("wkt", "POINT (114 22)\nPOINT (115 23)", Map.of())).features().size());
        for (var text : new String[]{"POINT (999 22)", "POINT (114 90)", "POINT Z (114 22 3)",
                "POINT (114 22 3)", "POINT EMPTY", "POLYGON ((0 0,1 1,0 1,1 0,0 0))"})
            assertThrows(ResponseStatusException.class, () -> parser.parse(input("wkt", text, Map.of())), text);
    }
    @Test void rejectsBadMappingAndMalformedCsv() {
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("csv", "wkt,name\nPOINT (114 22),a", Map.of("missing", "name"))));
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("csv", "wkt,name,name\nPOINT (114 22),a,b", Map.of())));
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("csv", "wkt,name\nPOINT (114 22),a,b", Map.of())));
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("wkt", "POINT (114 22)", Map.of("a","name","b","name"))));
    }
    @Test void rejectsNestedPropertiesAndMixedTypes() {
        String template = """
                {"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[114,22]},"properties":%s}]}
                """;
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("geojson", template.formatted("{\"x\":{\"a\":1}}"), Map.of())));
        assertThrows(ResponseStatusException.class, () -> parser.parse(input("geojson", template.formatted("{}").replace("[114,22]", "[114,22,0]"), Map.of())));
    }
}
