import type { Pool } from 'pg';
import { MotionEngine } from '../domain/motion.js';
import { loadMotionCheckpoint, loadMotionPaths, loadMotionRuns, saveMotionCheckpoint } from '../db/motion-queries.js';
import type { ServiceConfig } from '../domain/types.js';

export async function createMotionService(pool: Pool, service: ServiceConfig) {
    const owner = await pool.connect();
    const lock = 'mini-mtr-live-motion';
    try {
        const result = await owner.query('SELECT pg_try_advisory_lock(hashtext($1)) AS acquired', [lock]);
        if (!result.rows[0].acquired) throw new Error('Another live motion service already owns this database');
        return await initialize();
    } catch (error) {
        await owner.query('SELECT pg_advisory_unlock(hashtext($1))', [lock]);
        owner.release();
        throw error;
    }

    async function initialize() {
        const paths = await loadMotionPaths(pool);
        const engine = new MotionEngine(paths, new Map(service.lines.map(line => [line.lineId, line.speedKmph / 3.6])));
        const now = Date.now();
        engine.sync(await loadMotionRuns(pool, paths, now), now);
        const checkpoint = await loadMotionCheckpoint(pool);
        if (checkpoint) engine.restore(checkpoint, now);
        let refreshedAt = now, savedAt = 0;
        let pendingSave = Promise.resolve();
        const save = () => {
            const result = pendingSave.then(() => saveMotionCheckpoint(pool, engine.checkpoint(Date.now())));
            // Keep writes ordered even if one fails; each caller still receives its error.
            pendingSave = result.catch(() => {});
            return result;
        };
        return {
            observe: engine.observe.bind(engine),
            async loadSnapshot(at: Date) {
                const time = at.getTime();
                if (time - refreshedAt >= 60_000) {
                    engine.sync(await loadMotionRuns(pool, paths, time), time);
                    refreshedAt = time;
                }
                const snapshot = engine.snapshot(time);
                // Persist the trajectory, not just its last position, so restart can
                // evaluate the same timeline without resetting to the timetable.
                if (time - savedAt >= 10_000) {
                    await save();
                    savedAt = time;
                }
                return snapshot;
            },
            save,
            async close() {
                try { await save(); }
                finally {
                    await owner.query('SELECT pg_advisory_unlock(hashtext($1))', [lock]);
                    owner.release();
                }
            },
        };
    }
}

export type MotionService = Awaited<ReturnType<typeof createMotionService>>;
