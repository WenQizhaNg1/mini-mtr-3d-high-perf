package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.ImportResult;
import dev.minimtr.repo.LegacyImportRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyImportService {
    private final LegacyImportRepo repo;
    private final ServiceSettings settings;

    public LegacyImportService(LegacyImportRepo repo, ServiceSettings settings) {
        this.repo = repo;
        this.settings = settings;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ImportResult importSnapshot() {
        repo.requireEmptyTarget();
        repo.insertOperatorAndLines(settings);
        repo.copyLegacy();
        for (var line : settings.lines()) {
            line.patterns().forEach((direction, pattern) -> {
                repo.setDirection(pattern.defaultPattern(), direction);
                if (pattern.alternate() != null) repo.setDirection(pattern.alternate(), direction);
            });
        }
        return repo.validateAndCount();
    }
}
