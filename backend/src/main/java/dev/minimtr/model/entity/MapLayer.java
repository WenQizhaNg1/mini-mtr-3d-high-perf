package dev.minimtr.model.entity;

import tools.jackson.databind.JsonNode;

public record MapLayer(String code, String source, String sourceLayer, String type, int position,
        double minzoom, double maxzoom, JsonNode layout, JsonNode paint, JsonNode filter, boolean enabled) {}
