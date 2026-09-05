package dev.minimtr.model.vo;

import java.util.List;

public record WeatherVo(double icon, double temperatureC, double humidityPercent,
        List<String> warnings, String updatedAt, String cachedAt, boolean stale, String error) {}
