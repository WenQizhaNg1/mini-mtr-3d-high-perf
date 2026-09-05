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
import static dev.minimtr.service.DatasetParser.require;

@Service
public class DatasetService {
    private final DatasetRepo repo;
    private final DatasetParser parser;
    public DatasetService(DatasetRepo repo, DatasetParser parser) { this.repo = repo; this.parser = parser; }
    public List<JsonNode> list(boolean drafts) { return repo.list(drafts); }
    public List<JsonNode> ontology() { return repo.ontology(); }
    public JsonNode find(String code, boolean drafts) {
        var dataset = repo.find(code, drafts);
        if (dataset == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found");
        return dataset;
    }
    public Map<String, Object> preview(DatasetImport input) {
        var parsed = validate(input);
        return Map.of("count", parsed.features().size(), "fields", parsed.fields(),
                "samples", parsed.features().stream().limit(10).toList());
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
        if (published) checkOntology(dataset.path("ontology_code").asText(), repo.geometryTypes(code));
        repo.publish(code, published);
        return find(code, true);
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
