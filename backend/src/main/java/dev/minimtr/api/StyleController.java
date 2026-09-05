package dev.minimtr.api;

import dev.minimtr.model.vo.MapStyleVo;
import dev.minimtr.model.vo.MapStyleSummaryVo;
import dev.minimtr.service.StyleService;
import dev.minimtr.service.MapStyleService;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StyleController {
    private final StyleService styles;
    private final MapStyleService maps;

    public StyleController(StyleService styles, MapStyleService maps) {
        this.styles = styles; this.maps = maps;
    }

    @GetMapping("/api/styles/{code}/style.json")
    public ResponseEntity<ObjectNode> map(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.load(code));
    }
    @GetMapping("/api/map-sources")
    public ResponseEntity<ObjectNode> sources() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.catalog());
    }
    @GetMapping("/api/basemaps/{code}/style.json")
    public ResponseEntity<ObjectNode> basemap(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maps.basemap(code));
    }

    @PutMapping("/api/styles/{code}")
    public ResponseEntity<Void> save(@PathVariable String code, @RequestBody ObjectNode input) {
        maps.save(code, input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/styles")
    public ResponseEntity<List<MapStyleSummaryVo>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(styles.list());
    }

    @GetMapping("/api/styles/{code}")
    public ResponseEntity<MapStyleVo> load(@PathVariable String code) {
        return styles.load(code).map(style -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(style))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
