package dev.minimtr.service;

import dev.minimtr.repo.DatasetRepo;
import dev.minimtr.repo.StyleRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static dev.minimtr.service.DatasetParser.require;

/** Publishes registered sources and database layers as a native MapLibre style. */
@Service
public class MapStyleService {
    private static final Set<String> BASEMAPS = Set.of("positron", "osm-liberty-dark");
    private static final Set<String> TYPES = Set.of("line", "fill", "fill-extrusion", "circle", "symbol", "heatmap");
    private final StyleService styles;
    private final StyleRepo repo;
    private final DatasetRepo datasets;
    private final JsonMapper json;
    private final Path directory;
    private final String martin;

    public MapStyleService(StyleService styles, StyleRepo repo, DatasetRepo datasets, JsonMapper json,
            @Value("${workbench.map-directory:../map}") String directory,
            @Value("${workbench.martin-public-url:http://127.0.0.1:8081}") String martin) {
        this.styles = styles; this.repo = repo; this.datasets = datasets; this.json = json;
        Path path = Path.of(directory);
        this.directory = directory.equals("../map") && !Files.isDirectory(path) ? Path.of("map") : path;
        this.martin = martin.replaceAll("/+$", "");
        require(this.martin.matches("https?://[^?#]+"), "MARTIN_PUBLIC_URL must be an HTTP(S) base URL");
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ObjectNode load(String code) {
        var style = styles.load(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Style not found"));
        var output = basemap(style.basemap());
        output.put("name", style.name());
        var sources = (ObjectNode) output.get("sources");
        var baseLayers = output.withArray("layers");
        var routeLayers = json.createArrayNode();
        var overlayLayers = json.createArrayNode();
        var ids = new HashSet<String>();
        baseLayers.forEach(layer -> ids.add(layer.path("id").asText()));
        for (var layer : style.layers()) {
            String source = layer.path("source").asText();
            var definition = source(source);
            // Unpublishing a dataset removes it from newly requested styles.
            if (definition == null) continue;
            require(ids.add(layer.path("id").asText()), "Layer ID conflicts with basemap");
            sources.set(source, definition);
            if (source.equals("mtr") && layer.path("source-layer").asText().equals("mtr_routes")
                    && layer.path("type").asText().equals("line")) routeLayers.add(layer);
            else overlayLayers.add(layer);
        }
        var layers = json.createArrayNode();
        boolean inserted = false;
        for (var layer : baseLayers) {
            if (!inserted && layer.path("type").asText().equals("fill-extrusion")) {
                layers.addAll(routeLayers); inserted = true;
            }
            layers.add(layer);
        }
        if (!inserted) layers.addAll(routeLayers);
        layers.addAll(overlayLayers);
        output.set("layers", layers);
        output.set("state", json.readTree("{\"language\":{\"default\":\"zh\"}}"));
        return output;
    }

    @Transactional
    public void save(String code, ObjectNode input) {
        require(code.matches("[a-z][a-z0-9-]{0,63}"), "Invalid style code");
        String name = input.path("name").asText();
        String basemap = input.path("basemap").asText();
        require(!name.isBlank() && name.length() <= 200, "Style name is required (max 200 characters)");
        require(BASEMAPS.contains(basemap), "Unknown basemap");
        var layers = input.path("layers");
        require(layers.isArray() && layers.size() <= 100, "Expected at most 100 layers");
        require(input.toString().length() <= 512 * 1024, "Style exceeds 512 KiB");
        var ids = new HashSet<String>();
        basemap(basemap).path("layers").forEach(layer -> ids.add(layer.path("id").asText()));
        for (var layer : layers) {
            require(layer.isObject(), "Layer must be an object");
            String id = layer.path("id").asText();
            require(id.matches("[a-zA-Z][a-zA-Z0-9:_-]{0,99}") && ids.add(id), "Invalid or duplicate layer ID: " + id);
            String type = layer.path("type").asText();
            require(TYPES.contains(type), "Unsupported layer type");
            String source = layer.path("source").asText();
            require(source(source) != null, "Source is not published: " + source);
            if (source.equals("mtr")) require(Set.of("mtr_routes", "mtr_stations").contains(layer.path("source-layer").asText()), "Unknown MTR source-layer");
            else if (source.startsWith("dataset:")) {
                require(layer.path("source-layer").asText().equals("features"), "Dataset source-layer must be features");
                var geometries = datasets.geometryTypes(source.substring(8));
                if (type.equals("fill") || type.equals("fill-extrusion"))
                    require(geometries.stream().anyMatch(g -> g.contains("POLYGON")), "Fill layers require polygon data");
                if (type.equals("circle") || type.equals("heatmap"))
                    require(geometries.stream().anyMatch(g -> g.contains("POINT")), "Point layers require point data");
                if (type.equals("line"))
                    require(geometries.stream().anyMatch(g -> g.contains("LINESTRING") || g.contains("POLYGON")), "Line layers require line or polygon data");
            } else require(!layer.has("source-layer"), "GeoJSON source cannot have source-layer");
            double min = zoom(layer, "minzoom", 0), max = zoom(layer, "maxzoom", 24);
            require(min >= 0 && max <= 24 && min < max, "Invalid zoom range");
            for (String key : List.of("paint", "layout")) require(!layer.has(key) || layer.get(key).isObject(), key + " must be an object");
            require(!layer.has("filter") || layer.get("filter").isArray(), "filter must be an expression array");
            if (layer.path("layout").has("visibility")) require(Set.of("visible", "none").contains(layer.path("layout").path("visibility").asText()), "Invalid visibility");
            for (var property : layer.properties()) require(Set.of("id", "source", "source-layer", "type", "minzoom", "maxzoom", "paint", "layout", "filter").contains(property.getKey()), "Unsupported layer property: " + property.getKey());
        }
        repo.save(code, name, basemap, layers);
    }

    private static double zoom(JsonNode layer, String name, double fallback) {
        if (!layer.has(name)) return fallback;
        require(layer.get(name).isNumber(), name + " must be numeric");
        double value = layer.get(name).asDouble();
        require(Double.isFinite(value), "Invalid zoom");
        return value;
    }

    private ObjectNode source(String key) {
        var source = json.createObjectNode();
        if (key.equals("mtr-trains")) {
            source.put("type", "geojson").put("promoteId", "id");
            source.set("data", json.readTree("{\"type\":\"FeatureCollection\",\"features\":[]}"));
        } else if (key.equals("mtr")) {
            source.put("type", "vector").put("url", martin + "/mtr-routes,mtr-stations");
        } else if (key.startsWith("dataset:")) {
            String code = key.substring(8);
            if (datasets.find(code, false) == null) return null;
            source.put("type", "vector").put("minzoom", 0).put("maxzoom", 22);
            source.putArray("tiles").add(martin + "/features/{z}/{x}/{y}?dataset=" + code);
        } else throw DatasetParser.bad("Unregistered source: " + key);
        return source;
    }

    public ObjectNode basemap(String code) {
        require(BASEMAPS.contains(code), "Unknown basemap");
        try {
            var base = (ObjectNode) json.readTree(Files.readString(directory.resolve("style").resolve(code).resolve("style.json")));
            for (String key : List.of("glyphs", "sprite")) if (base.path(key).isString()) base.put(key, absolute(base.get(key).asText()));
            for (var entry : base.path("sources").properties()) {
                var source = (ObjectNode) entry.getValue();
                if (source.has("url")) source.put("url", absolute(source.get("url").asText()));
                if (source.has("tiles")) {
                    var tiles = json.createArrayNode();
                    source.path("tiles").forEach(tile -> tiles.add(absolute(tile.asText())));
                    source.set("tiles", tiles);
                }
            }
            if (code.equals("osm-liberty-dark"))
                base.set("light", json.readTree("{\"anchor\":\"viewport\",\"color\":\"#ffffff\",\"intensity\":0.4,\"position\":[1.5,210,40]}"));
            return base;
        } catch (IOException e) { throw new IllegalStateException("Cannot read configured basemap: " + code, e); }
    }
    private String absolute(String value) { return value.startsWith("/") ? martin + value : value; }

    public ObjectNode catalog() {
        var result = json.createObjectNode();
        var mtr = result.putObject("mtr");
        mtr.put("name", "港铁").set("source", source("mtr"));
        mtr.set("layers", json.readTree("""
                [{"id":"mtr_routes","geometry":"LineString","fields":{"line_id":"string","colour":"string","id":"string"}},
                 {"id":"mtr_stations","geometry":"Point","fields":{"code":"string","name_zh":"string","name_en":"string","interchange":"boolean"}}]
                """));
        var trains = result.putObject("mtr-trains");
        trains.put("name", "实时列车（工作台使用示意要素）").set("source", source("mtr-trains"));
        trains.set("layers", json.readTree("[{\"id\":\"\",\"geometry\":\"Polygon\",\"fields\":{\"colour\":\"string\",\"height\":\"number\"}}]"));
        for (var dataset : datasets.list(false)) {
            String code = dataset.path("code").asText();
            var entry = result.putObject("dataset:" + code);
            entry.put("name", dataset.path("name").asText()).set("source", source("dataset:" + code));
            entry.set("bounds", json.valueToTree(datasets.bounds(code)));
            var layer = entry.putArray("layers").addObject();
            layer.put("id", "features");
            layer.put("geometry", String.join(",", datasets.geometryTypes(code)));
            layer.set("fields", dataset.path("fields"));
        }
        return result;
    }
}
