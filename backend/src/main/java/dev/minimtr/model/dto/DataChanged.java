package dev.minimtr.model.dto;

/** Published inside the write transaction so business dependencies can be rebuilt atomically. */
public record DataChanged(String dataset) {}
