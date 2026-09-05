import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { createDatabase } from '../src/db/client.js';
import { loadMotionPaths, loadMotionRuns } from '../src/db/motion-queries.js';
import { MotionEngine, distance, progress } from '../src/domain/motion.js';
import { normalizeArrivals } from '../src/domain/realtime.js';
import { parseHkTimestamp } from '../src/domain/time.js';
import { readServiceConfig } from '../src/config.js';
import type { Monitor, MtrScheduleResponse } from '../src/domain/types.js';

test('recorded MOK ETA sequence on real PostGIS routes preserves progress and survives restart', {
    skip: process.env.DATABASE_URL ? false : 'DATABASE_URL is not set',
}, async () => {
    const database = createDatabase(process.env.DATABASE_URL!);
    try {
        const observations: MtrScheduleResponse[] = JSON.parse(readFileSync(new URL('./fixtures/twl-mok-eta.json', import.meta.url), 'utf8'));
        const config = readServiceConfig();
        const line = config.lines.find(l => l.lineId === 'TWL')!;
        const monitor: Monitor = { lineId: line.lineId, apiCode: line.apiCode, stationCode: line.monitorStation, patterns: line.patterns };
        const paths = await loadMotionPaths(database.pool);
        const speeds = new Map(config.lines.map(line => [line.lineId, line.speedKmph / 3.6]));
        const start = parseHkTimestamp(observations[0]!.sys_time!)!.getTime();
        const runs = await loadMotionRuns(database.pool, paths, start);
        const engine = new MotionEngine(paths, speeds);
        engine.sync(runs, start);
        let matched = 0;
        for (const response of observations) {
            const now = parseHkTimestamp(response.sys_time!)!.getTime();
            const sourceAt = parseHkTimestamp(response.curr_time!)!.getTime();
            const before = engine.snapshot(now);
            for (const direction of ['UP', 'DOWN'] as const) {
                matched += engine.observe(monitor, direction, normalizeArrivals(response, monitor, direction), sourceAt, now);
            }
            const after = engine.snapshot(now);
            assert.deepEqual(after.trains.map(t => [t.id, t.lng, t.lat]), before.trains.map(t => [t.id, t.lng, t.lat]));
            const old = new Map(after.trains.map(t => [t.id, t]));
            for (const train of engine.snapshot(now + 1000).trains) {
                const previous = old.get(train.id);
                if (!previous) continue;
                assert.ok(distance([previous.lng, previous.lat], [train.lng, train.lat]) <= speeds.get(train.lineId)! + 0.01, train.id);
            }
            for (const state of engine.states.values()) assert.ok(progress(state.nodes, now + 1000) >= progress(state.nodes, now));
            const restored = new MotionEngine(paths, speeds);
            restored.sync(runs, start);
            restored.restore(JSON.parse(JSON.stringify(engine.checkpoint(now))), now + 2000);
            assert.deepEqual(restored.snapshot(now + 2000), engine.snapshot(now + 2000));
        }
        assert.ok(matched > 0, 'real ETA fixtures must exercise accepted corrections');
    } finally { await database.close(); }
});
