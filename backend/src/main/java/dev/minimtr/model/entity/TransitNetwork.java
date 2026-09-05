package dev.minimtr.model.entity;

import dev.minimtr.service.RoutePath;
import java.util.List;
import java.util.Map;

public record TransitNetwork(Map<String, Path> paths) {
    public record Path(long id, String code, String lineId, String colour, String direction,
            RoutePath geometry, List<Stop> stops) {}
    public record Stop(int seq, String code, double distanceMeters) {}
}
