package dev.minimtr.model.entity;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

public record ParsedDataset(List<Feature> features, Map<String, String> fields) {
    public record Feature(String wkt, ObjectNode properties, String geometryType) {}
}
