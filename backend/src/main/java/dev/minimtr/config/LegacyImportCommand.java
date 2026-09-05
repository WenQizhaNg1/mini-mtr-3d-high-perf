package dev.minimtr.config;

import dev.minimtr.service.LegacyImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "migration.import-legacy", havingValue = "true")
public class LegacyImportCommand implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyImportCommand.class);
    private final LegacyImportService service;
    private final ConfigurableApplicationContext context;

    public LegacyImportCommand(LegacyImportService service, ConfigurableApplicationContext context) {
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Legacy snapshot imported: {}", service.importSnapshot());
        context.close();
    }
}
