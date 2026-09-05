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
    @GetMapping("/api/admin/datasets/{code}/features")
    public ResponseEntity<Map<String,Object>> features(@PathVariable String code) { return response(datasets.features(code)); }
    @PutMapping("/api/admin/datasets/{code}/features/{key}")
    public ResponseEntity<Map<String,Object>> saveFeature(@PathVariable String code, @PathVariable String key, @RequestBody JsonNode input) {
        return response(datasets.saveFeature(code, key, input));
    }
    @GetMapping("/api/admin/datasets/{code}/features/{key}")
    public ResponseEntity<Object> feature(@PathVariable String code,@PathVariable String key) {
        return response(datasets.feature(code,key));
    }
    @DeleteMapping("/api/admin/datasets/{code}/features/{key}")
    public ResponseEntity<Void> deleteFeature(@PathVariable String code, @PathVariable String key) {
        datasets.deleteFeature(code, key); return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/admin/datasets/{code}/import")
    public ResponseEntity<Map<String,Object>> update(@PathVariable String code, @RequestParam(defaultValue="merge") String mode,
            @RequestBody DatasetImport input) { return response(datasets.update(code, input, mode)); }
    public record Publication(Boolean published) {}
    @PutMapping("/api/datasets/{code}/publication")
    public ResponseEntity<JsonNode> publish(@PathVariable String code, @RequestBody Publication input) {
        dev.minimtr.service.DatasetParser.require(input.published() != null, "published is required");
        return response(datasets.publish(code, input.published()));
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
