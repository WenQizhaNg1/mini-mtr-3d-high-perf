package dev.minimtr.service;

import dev.minimtr.model.vo.WeatherVo;
import dev.minimtr.repo.WeatherClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class WeatherService {
    private static class Cache {
        WeatherVo value;
        Instant expiresAt = Instant.MIN;
    }

    private final Map<String, Cache> cache = Map.of("zh", new Cache(), "en", new Cache());
    private final WeatherClient client;
    private final Clock clock;

    public WeatherService(WeatherClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public WeatherVo load(String language) {
        var entry = cache.get(language);
        if (entry == null) throw new IllegalArgumentException("Invalid lang; expected en or zh");
        // One refresh per language; concurrent requests share its result.
        synchronized (entry) {
            var now = clock.instant();
            if (now.isBefore(entry.expiresAt)) return entry.value;
            try {
                entry.value = normalize(client.current(language), now);
                entry.expiresAt = now.plusSeconds(600);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                LoggerFactory.getLogger(WeatherService.class).warn("HKO weather refresh failed ({})", language, error);
                if (entry.value == null) throw new IllegalStateException("Weather data unavailable", error);
                var old = entry.value;
                entry.value = new WeatherVo(old.icon(), old.temperatureC(), old.humidityPercent(), old.warnings(),
                        old.updatedAt(), old.cachedAt(), true, error.getMessage());
                entry.expiresAt = now.plusSeconds(60);
            }
            return entry.value;
        }
    }

    static WeatherVo normalize(JsonNode weather, Instant now) {
        if (weather == null || !weather.isObject()) throw new IllegalArgumentException("HKO weather response must be an object");
        var temperatures = weather.path("temperature").path("data");
        var temperature = temperatures.path(0);
        for (var row : temperatures) {
            var place = row.path("place").asText("");
            if (place.toLowerCase(java.util.Locale.ROOT).contains("observatory") || place.contains("天文台")) {
                temperature = row;
                break;
            }
        }
        var updatedAt = weather.path("updateTime").asText("");
        if (updatedAt.isBlank()) throw new IllegalArgumentException("HKO weather update time is missing");
        var warnings = new ArrayList<String>();
        if (weather.path("warningMessage").isArray())
            for (var warning : weather.path("warningMessage")) if (warning.isString()) warnings.add(warning.asText());
        var icon = weather.path("icon");
        return new WeatherVo(number(icon.isArray() ? icon.path(0) : icon, "icon"),
                number(temperature.path("value"), "temperature"),
                number(weather.path("humidity").path("data").path(0).path("value"), "humidity"),
                warnings, updatedAt, now.toString(), false, null);
    }

    private static double number(JsonNode value, String label) {
        try {
            if (!value.isNumber() && !value.isString()) throw new NumberFormatException();
            double number = Double.parseDouble(value.asText());
            if (Double.isFinite(number)) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("HKO weather " + label + " is missing or invalid");
    }
}
