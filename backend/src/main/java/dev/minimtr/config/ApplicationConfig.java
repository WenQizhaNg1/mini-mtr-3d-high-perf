package dev.minimtr.config;

import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ApplicationConfig {
    @Bean
    ServiceSettings serviceSettings(JsonMapper mapper) throws IOException {
        try (var input = new ClassPathResource("mtr-service.json").getInputStream()) {
            return mapper.readValue(input, ServiceSettings.class);
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
