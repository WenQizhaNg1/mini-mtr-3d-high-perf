package dev.minimtr.model.dto;

import java.util.Map;

public record DatasetImport(String code, String name, String ontology, String format, String content,
        String geometryColumn, String longitudeColumn, String latitudeColumn, Map<String, String> mapping) {}
