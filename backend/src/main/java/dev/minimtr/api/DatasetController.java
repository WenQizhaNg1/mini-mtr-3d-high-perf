package dev.minimtr.api;

import dev.minimtr.model.dto.DatasetImport;
import dev.minimtr.service.DatasetService;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public class DatasetController {
    private final DatasetService datasets;
    public DatasetController(DatasetService datasets) { this.datasets = datasets; }
    @GetMapping("/api/ontology")
    public ResponseEntity<List<JsonNode>> ontology() { return response(datasets.ontology()); }
    @GetMapping("/api/datasets")
    public ResponseEntity<List<JsonNode>> list() { return response(datasets.list(false)); }
    @GetMapping("/api/datasets/{code}")
    public ResponseEntity<JsonNode> find(@PathVariable String code) { return response(datasets.find(code, false)); }
    @GetMapping("/api/admin/datasets")
    public ResponseEntity<List<JsonNode>> admin() {
        return response(datasets.list(true));
    }
    @PostMapping("/api/datasets/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody DatasetImport input) { return response(datasets.preview(input)); }
    @PostMapping("/api/datasets/inspect")
    public ResponseEntity<Map<String, Object>> inspect(@RequestBody DatasetImport input) { return response(datasets.inspect(input)); }
    @PostMapping("/api/datasets")
    public ResponseEntity<JsonNode> create(@RequestBody DatasetImport input) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(datasets.create(input)); }
    public record Publication(Boolean published) {}
    @PutMapping("/api/datasets/{code}/publication")
    public ResponseEntity<JsonNode> publish(@PathVariable String code, @RequestBody Publication input) {
        dev.minimtr.service.DatasetParser.require(input.published() != null, "published is required");
        return response(datasets.publish(code, input.published()));
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
