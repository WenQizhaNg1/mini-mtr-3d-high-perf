package dev.minimtr.service;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.model.entity.ParsedDataset;
import dev.minimtr.model.entity.ParsedDataset.Feature;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DatasetParser {
    private final JsonMapper json;
    public DatasetParser(JsonMapper json) { this.json = json; }

    public ParsedDataset parse(DatasetImport input) {
        require(input != null && input.content() != null && input.format() != null, "format and content are required");
        require(input.content().getBytes(StandardCharsets.UTF_8).length <= 5 * 1024 * 1024, "Import exceeds 5 MiB");
        Map<String, String> mapping = input.mapping() == null ? Map.of() : input.mapping();
        require(new HashSet<>(mapping.values()).size() == mapping.size(), "Duplicate mapping targets");
        mapping.forEach((from, to) -> require(from != null && !from.isBlank() && validField(to), "Invalid field mapping"));
        var rows = new ArrayList<Feature>();
        var fields = new LinkedHashMap<String, String>();
        var seen = new HashSet<String>();
        int[] points = {0};
        try {
            switch (input.format()) {
                case "geojson" -> {
                    var root = json.readTree(input.content());
                    require(root != null && !root.has("crs"), "GeoJSON must use WGS84 without a custom CRS");
                    require("FeatureCollection".equals(root.path("type").asText()) && root.path("features").isArray(),
                            "Expected a GeoJSON FeatureCollection");
                    for (var feature : root.path("features")) {
                        require("Feature".equals(feature.path("type").asText()), "Expected GeoJSON Feature");
                        var geom = feature.path("geometry");
                        require(geom.isObject() && !geom.has("crs") && !feature.has("crs"), "Geometry must be WGS84");
                        validateCoordinates(geom.path("coordinates"));
                        var properties = feature.get("properties");
                        require(properties == null || properties.isNull() || properties.isObject(), "properties must be an object");
                        add(rows, fields, seen, points, new GeoJsonReader().read(geom.toString()),
                                properties == null || properties.isNull() ? json.createObjectNode() : (ObjectNode) properties, mapping);
                    }
                }
                case "wkt" -> {
                    require(mapping.isEmpty(), "Pure WKT has no property fields to map");
                    for (String line : input.content().lines().filter(s -> !s.isBlank()).toList())
                        add(rows, fields, seen, points, readWkt(line), json.createObjectNode(), mapping);
                }
                case "csv" -> {
                    var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
                    String content = input.content().replaceFirst("^\\uFEFF", "");
                    try (var csv = format.parse(new StringReader(content))) {
                        var headers = csv.getHeaderNames();
                        require(new HashSet<>(headers).size() == headers.size() && headers.stream().noneMatch(String::isBlank), "CSV headers must be unique and nonempty");
                        boolean wkt = input.geometryColumn() != null && !input.geometryColumn().isBlank();
                        var geometryFields = wkt ? List.of(input.geometryColumn())
                                : Arrays.asList(input.longitudeColumn(), input.latitudeColumn());
                        require(geometryFields.stream().allMatch(h -> h != null && headers.contains(h)), "Select existing geometry columns");
                        require(new HashSet<>(geometryFields).size() == geometryFields.size(), "Longitude and latitude columns must differ");
                        for (var row : csv) {
                            require(row.isConsistent(), "CSV row " + row.getRecordNumber() + " has wrong column count");
                            var props = json.createObjectNode();
                            for (String header : headers) if (!geometryFields.contains(header)) props.put(header, row.get(header));
                            String geometry = wkt ? row.get(input.geometryColumn()) : "POINT ("
                                    + Double.parseDouble(row.get(input.longitudeColumn())) + " "
                                    + Double.parseDouble(row.get(input.latitudeColumn())) + ")";
                            add(rows, fields, seen, points, readWkt(geometry), props, mapping);
                        }
                    }
                }
                default -> throw bad("format must be geojson, csv or wkt");
            }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid " + input.format() + " near feature " + (rows.size() + 1) + ": " + e.getMessage(), e); }
        require(!rows.isEmpty(), "Dataset is empty");
        require(seen.containsAll(mapping.keySet()), "A mapped source field does not exist");
        fields.replaceAll((key, type) -> type.equals("null") ? "string" : type);
        return new ParsedDataset(List.copyOf(rows), fields);
    }

    private Geometry readWkt(String value) throws Exception {
        require(!value.toUpperCase(Locale.ROOT).matches("(?s).*\\b(Z|M|ZM)\\b.*"), "Only 2D WKT is supported");
        var reader = new WKTReader();
        reader.setIsOldJtsCoordinateSyntaxAllowed(false);
        return reader.read(value);
    }

    private void validateCoordinates(JsonNode node) {
        require(node.isArray() && !node.isEmpty(), "Geometry coordinates are required");
        if (node.get(0).isNumber()) {
            require(node.size() == 2 && node.get(1).isNumber(), "Only 2D coordinates are supported");
        } else for (var child : node) validateCoordinates(child);
    }

    private void add(List<Feature> rows, Map<String, String> fields, Set<String> seen, int[] points,
            Geometry geom, ObjectNode properties, Map<String, String> mapping) {
        require(rows.size() < 10000, "Import exceeds 10000 features");
        points[0] += geom.getNumPoints();
        require(points[0] <= 200000, "Import exceeds 200000 coordinates");
        require(!geom.isEmpty() && !geom.getGeometryType().equals("GeometryCollection"), "Empty/collection geometries are unsupported");
        var validity = new IsValidOp(geom).getValidationError();
        require(validity == null, "Invalid geometry at feature " + (rows.size() + 1) + ": " + validity);
        for (var coordinate : geom.getCoordinates()) {
            require(Double.isFinite(coordinate.x) && Double.isFinite(coordinate.y)
                    && Math.abs(coordinate.x) <= 180 && Math.abs(coordinate.y) <= 85.05112878
                    && Double.isNaN(coordinate.getZ()) && Double.isNaN(coordinate.getM()),
                    "Expected 2D WGS84 coordinates within Web Mercator bounds at feature " + (rows.size() + 1));
        }
        var mapped = json.createObjectNode();
        for (var entry : properties.properties()) {
            seen.add(entry.getKey());
            String key = mapping.isEmpty() ? entry.getKey() : mapping.get(entry.getKey());
            if (key == null) continue;
            require(validField(key), "Invalid property name: " + key);
            var value = entry.getValue();
            require(value.isValueNode(), "Nested property is unsupported: " + key);
            String type = value.isNull() ? "null" : value.isNumber() ? "number" : value.isBoolean() ? "boolean" : "string";
            String previous = fields.get(key);
            require(previous == null || previous.equals("null") || type.equals("null") || previous.equals(type), "Mixed property types: " + key);
            if (previous == null || previous.equals("null")) fields.put(key, type);
            mapped.set(key, value);
        }
        rows.add(new Feature(geom.toText(), mapped, geom.getGeometryType()));
    }

    private static boolean validField(String value) {
        return value != null && !value.isBlank() && value.length() <= 100
                && !Set.of("geom", "feature_id").contains(value);
    }
    public static void require(boolean condition, String message) { if (!condition) throw bad(message); }
    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
