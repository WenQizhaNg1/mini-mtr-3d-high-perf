package dev.minimtr.model.entity;

public record RouteStop(long routeId, String routeCode, String lineCode, int seq, double distanceM) {}
