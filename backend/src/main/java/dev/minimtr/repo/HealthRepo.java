package dev.minimtr.repo;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class HealthRepo {
    private final DSLContext db;

    public HealthRepo(DSLContext db) {
        this.db = db;
    }

    public void check() {
        db.selectOne().fetchSingle();
    }
}
