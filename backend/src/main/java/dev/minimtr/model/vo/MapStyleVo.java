package dev.minimtr.model.vo;

import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public record MapStyleVo(String code, String name, String basemap, List<ObjectNode> layers, tools.jackson.databind.JsonNode metadata) {}
