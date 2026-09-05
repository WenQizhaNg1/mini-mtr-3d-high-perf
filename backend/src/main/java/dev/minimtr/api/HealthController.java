package dev.minimtr.api;

import dev.minimtr.repo.HealthRepo;
import dev.minimtr.model.vo.HealthVo;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final HealthRepo repo;

    public HealthController(HealthRepo repo) {
        this.repo = repo;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthVo> health() {
        try {
            repo.check();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HealthVo("ok", "up"));
        } catch (DataAccessException | org.springframework.dao.DataAccessException exception) {
            log.warn("Database health check failed", exception);
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new HealthVo("error", "down"));
        }
    }

}
