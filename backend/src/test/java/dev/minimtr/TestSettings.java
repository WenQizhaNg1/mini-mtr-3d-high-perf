package dev.minimtr;

import dev.minimtr.config.ServiceSettings;
import tools.jackson.databind.json.JsonMapper;

public final class TestSettings {
    private TestSettings() {}

    public static ServiceSettings load() {
        try (var input = TestSettings.class.getResourceAsStream("/mtr-service.json")) {
            return JsonMapper.builder().build().readValue(input, ServiceSettings.class);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
