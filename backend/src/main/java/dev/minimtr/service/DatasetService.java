package dev.minimtr.service;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.model.entity.ParsedDataset;
import dev.minimtr.repo.DatasetRepo;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import dev.minimtr.model.dto.DataChanged;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class DatasetService {
    private final DatasetRepo repo;
    private final DatasetParser parser;
    private final tools.jackson.databind.json.JsonMapper json;
    private final ApplicationEventPublisher events;
    public DatasetService(DatasetRepo repo, DatasetParser parser, tools.jackson.databind.json.JsonMapper json, ApplicationEventPublisher events) {
        this.repo = repo; this.parser = parser; this.json = json; this.events = events;
    }
    public List<JsonNode> list(boolean drafts) { return repo.list(drafts); }
    public List<JsonNode> ontology() { return repo.ontology(); }
    public Map<String, Object> inspect(DatasetImport input) { return parser.inspect(input); }
    public JsonNode find(String code, boolean drafts) {
        var dataset = repo.find(code, drafts);
        if (dataset == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found");
        return dataset;
    }
    public Map<String, Object> preview(DatasetImport input) {
        var parsed = validate(input);
        var features = new ArrayList<Map<String,Object>>();
        try {
            var reader = new org.locationtech.jts.io.WKTReader();
            var writer = new org.locationtech.jts.io.geojson.GeoJsonWriter();
            writer.setEncodeCRS(false);
            for (var feature : parsed.features().stream().limit(10).toList())
                features.add(Map.of("type", "Feature", "id", feature.sourceKey() == null ? "sample-" + features.size() : feature.sourceKey(),
                        "geometry", json.readTree(writer.write(reader.read(feature.wkt()))),
                        "properties", feature.properties()));
        } catch (org.locationtech.jts.io.ParseException e) { throw new IllegalStateException("Validated geometry cannot be read", e); }
        return Map.of("count", parsed.features().size(), "fields", parsed.fields(),
                "samples", parsed.features().stream().limit(10).toList(),
                "geojson", Map.of("type", "FeatureCollection", "features", features));
    }
    @Transactional
    public JsonNode create(DatasetImport input) {
        var parsed = validate(input);
        if (repo.find(input.code(), true) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dataset code already exists");
        repo.insert(input, parsed);
        return find(input.code(), true);
    }
    @Transactional
    public JsonNode publish(String code, boolean published) {
        var dataset = find(code, true);
        if(dataset.path("published").asBoolean()==published) return dataset;
        if (published) checkOntology(dataset.path("ontology_code").asText(), repo.geometryTypes(code));
        repo.publish(code, published);
        events.publishEvent(new DataChanged(code));
        return find(code, true);
    }
    public Map<String, Object> features(String code) {
        find(code, true);
        return Map.of("type", "FeatureCollection", "features", repo.features(code));
    }
    public JsonNode feature(String code,String key) {
        find(code,true);
        return repo.feature(code,key)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Feature not found: "+key));
    }
    @Transactional
    public Map<String, Object> update(String code, DatasetImport input, String mode) {
        var dataset = find(code, true);
        require(Set.of("merge", "replace").contains(mode), "mode must be merge or replace");
        var parsed = parser.parse(input);
        require(parsed.features().stream().allMatch(f -> f.sourceKey() != null), "Updating a dataset requires a source key for every feature");
        checkOntology(dataset.path("ontology_code").asText(), parsed.features().stream().map(ParsedDataset.Feature::geometryType).distinct().toList());
        long id = repo.lock(code);
        var keys = new HashSet<String>();
        for (var feature : parsed.features()) { keys.add(feature.sourceKey()); repo.upsert(id, feature); }
        if (mode.equals("replace")) for (var feature : repo.features(code))
            if (!keys.contains(feature.path("id").asText())) remove(id, feature);
        repo.refreshFields(id);
        events.publishEvent(new DataChanged(code));
        return features(code);
    }
    @Transactional
    public Map<String, Object> saveFeature(String code, String key, JsonNode feature) {
        var dataset = find(code, true);
        require(feature.isObject(), "Expected a GeoJSON feature");
        var copy = ((tools.jackson.databind.node.ObjectNode) feature).deepCopy();
        copy.put("id", key);
        var input = new DatasetImport(code, dataset.path("name").asText(), dataset.path("ontology_code").asText(),
                "geojson", json.writeValueAsString(Map.of("type", "FeatureCollection", "features", List.of(copy))),
                null, null, null, Map.of());
        return update(code, input, "merge");
    }
    @Transactional
    public void deleteFeature(String code, String key) {
        find(code, true);
        long id = repo.lock(code);
        var feature = repo.features(code).stream().filter(f -> f.path("id").asText().equals(key)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feature not found: " + key));
        remove(id, feature);
        repo.refreshFields(id);
        events.publishEvent(new DataChanged(code));
    }
    private void remove(long dataset, JsonNode feature) {
        long object = Long.parseLong(feature.path("objectId").asText());
        var dependencies = repo.dependencies(object);
        if (!dependencies.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Feature is referenced by " + String.join(", ", dependencies) + "; remove the business binding first");
        repo.delete(dataset, feature.path("id").asText(), object);
    }
    private ParsedDataset validate(DatasetImport input) {
        require(input.code() != null && input.code().matches("[a-z][a-z0-9-]{0,63}"), "Invalid dataset code");
        require(input.name() != null && !input.name().isBlank() && input.name().length() <= 200, "Dataset name is required (max 200 characters)");
        var parsed = parser.parse(input);
        checkOntology(input.ontology(), parsed.features().stream().map(ParsedDataset.Feature::geometryType).distinct().toList());
        return parsed;
    }
    private void checkOntology(String code, List<String> types) {
        var ontology = repo.ontology().stream().filter(o -> o.path("code").asText().equals(code)).findFirst()
                .orElseThrow(() -> DatasetParser.bad("Unknown ontology"));
        require(ontology.path("enabled").asBoolean() && !ontology.path("is_dynamic").asBoolean(), "Ontology must be enabled and static");
        var allowed = new HashSet<String>();
        ontology.path("geometry_types").forEach(t -> allowed.add(t.asText().toUpperCase(Locale.ROOT)));
        require(types.stream().allMatch(t -> allowed.contains(t.toUpperCase(Locale.ROOT))), "Geometry type is not supported by ontology");
    }
}
