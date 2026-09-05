package dev.minimtr.service;

import dev.minimtr.repo.ScheduleRepo;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {
    private final ScheduleRepo repo;
    private final ScheduleGenerator generator;

    public ScheduleService(ScheduleRepo repo, ScheduleGenerator generator) {
        this.repo = repo;
        this.generator = generator;
    }

    @Transactional
    public boolean ensure(LocalDate date) {
        // The existence check must run after the lock so concurrent preparations see the committed plan.
        repo.lock(date);
        if (repo.exists(date)) return false;
        repo.insert(date, generator.generate(date, repo.routeStops()));
        return true;
    }
}
