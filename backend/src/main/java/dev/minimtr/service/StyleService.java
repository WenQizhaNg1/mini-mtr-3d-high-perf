package dev.minimtr.service;

import dev.minimtr.model.vo.MapStyleVo;
import dev.minimtr.model.vo.MapStyleSummaryVo;
import dev.minimtr.repo.StyleRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class StyleService {
    private final StyleRepo repo;
    private final JsonMapper json;

    public StyleService(StyleRepo repo, JsonMapper json) { this.repo = repo; this.json = json; }

    public List<MapStyleSummaryVo> list() {
        return repo.list().stream().map(s -> new MapStyleSummaryVo(s.code(), s.name(), s.basemap())).toList();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<MapStyleVo> load(String code) {
        return repo.find(code).map(style -> {
            var layers = repo.layers(style.id()).stream().map(layer -> {
                var node = json.createObjectNode();
                node.put("id", layer.code()).put("type", layer.type()).put("source", layer.source())
                        .put("minzoom", layer.minzoom()).put("maxzoom", layer.maxzoom());
                if (layer.sourceLayer() != null) node.put("source-layer", layer.sourceLayer());
                ObjectNode layout = (ObjectNode) layer.layout().deepCopy();
                if (!layer.enabled()) layout.put("visibility", "none");
                node.set("layout", layout);
                node.set("paint", layer.paint());
                if (layer.filter() != null && !layer.filter().isNull()) node.set("filter", layer.filter());
                return node;
            }).toList();
            return new MapStyleVo(style.code(), style.name(), style.basemap(), layers);
        });
    }
}
