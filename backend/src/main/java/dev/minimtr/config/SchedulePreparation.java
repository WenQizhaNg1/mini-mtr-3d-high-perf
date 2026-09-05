package dev.minimtr.config;

import dev.minimtr.service.ScheduleService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@org.springframework.core.annotation.Order(10)
@EnableScheduling
@ConditionalOnExpression("${schedule.enabled:true} && !${migration.import-legacy:false}")
public class SchedulePreparation implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchedulePreparation.class);
    private final ScheduleService service;
    private final ServiceSettings settings;
    private final Clock clock;

    public SchedulePreparation(ScheduleService service, ServiceSettings settings, Clock clock) {
        this.service = service;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        prepare();
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void refresh() {
        try {
            prepare();
        } catch (RuntimeException error) {
            log.error("Schedule preparation failed; retrying next minute", error);
        }
    }

    private synchronized void prepare() {
        var date = settings.serviceDateAt(clock.instant());
        for (var serviceDate : java.util.List.of(date, date.plusDays(1))) {
            if (service.ensure(serviceDate)) log.info("Prepared simulated schedule for {}", serviceDate);
        }
    }
}
