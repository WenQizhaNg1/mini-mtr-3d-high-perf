import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from 'pg';
import { readServiceConfig } from '../src/config.js';
import { createDatabase } from '../src/db/client.js';
import { NETWORK_DATA_LOCK } from '../src/db/locks.js';
import { loadCandidates, updateOffsets } from '../src/db/realtime-queries.js';
import { loadTrainPositions } from '../src/db/train-queries.js';
import type { Monitor } from '../src/domain/types.js';
import { matchArrivals } from '../src/domain/realtime.js';
import { readNetworkData } from '../src/integrations/osm-files.js';
import { importNetwork, UnsafeNetworkChangeError } from '../src/services/network-import-service.js';
import { createNetworkService } from '../src/services/network-service.js';
import { ensureSchedule, generateScheduleForDate } from '../src/services/schedule-service.js';
import { ensureMissingSchedule } from '../src/db/schedule-queries.js';
import { createServiceDayService } from '../src/services/service-day-service.js';
import { createTrainService } from '../src/services/train-service.js';

const connectionString = process.env.DATABASE_URL;

test('Drizzle queries work with the existing PostGIS schema', {
    skip: connectionString ? false : 'DATABASE_URL is not set',
}, async () => {
    const database = createDatabase(connectionString!);
    try {
        const positions = await loadTrainPositions(database.db, new Date('2026-09-04T00:00:00.000Z'));
        assert.ok(positions.length > 0);
        assert.ok(positions[0]!.previous_time);
        assert.ok(positions[0]!.next_time);

        const snapshot = await createTrainService(database.db)
            .loadSnapshot(new Date('2026-09-04T00:00:00.000Z'));
        const firstTrain = snapshot.trains[0]!;
        assert.ok(Number.isFinite(new Date(firstTrain.previousTime).getTime()));
        assert.ok(Number.isFinite(new Date(firstTrain.nextTime!).getTime()));
        assert.ok(new Date(firstTrain.previousTime) < new Date(firstTrain.nextTime!));

        const config = readServiceConfig();
        const catalog = await createNetworkService(database.db, config).loadCatalog();
        assert.equal(catalog.lines.length, 10);
        assert.equal(catalog.stations.length, 98);
        assert.equal(catalog.lines[0]!.nameEn, 'Island Line');

        const serviceDay = await createServiceDayService(database.db, config)
            .load(new Date('2026-09-04T00:00:00.000Z'));
        assert.equal(serviceDay.serviceDate, '2026-09-04');
        assert.equal(serviceDay.active, true);
        assert.equal(serviceDay.schedules.current, true);
        const next = await database.pool.query('SELECT EXISTS(SELECT 1 FROM mtr.train_runs WHERE service_date = $1) AS present', ['2026-09-05']);
        assert.equal(serviceDay.schedules.next, next.rows[0].present);

        const monitor: Monitor = {
            lineId: 'TWL',
            apiCode: 'TWL',
            stationCode: 'MOK',
            patterns: {
                UP: { default: 'TWL:CEN-TSW' },
                DOWN: { default: 'TWL:TSW-CEN' },
            },
        };
        const candidates = await loadCandidates(
            database.db,
            monitor,
            'UP',
            new Date('2026-09-04T00:00:00.000Z'),
            new Date('2026-09-04T01:00:00.000Z'),
        );
        assert.ok(candidates.length > 0);
        assert.ok(candidates.every(candidate => candidate.scheduledAt instanceof Date
            && Number.isFinite(candidate.scheduledAt.getTime())));
        const candidate = candidates[0]!;
        const matches = matchArrivals(candidates, [{
            destination: candidate.destination,
            at: new Date(candidate.scheduledAt.getTime() + 12_000),
        }]);
        assert.deepEqual(matches, [{ trainId: candidate.id, delaySeconds: 12 }]);

        await assert.rejects(database.db.transaction(async transaction => {
            const replayAt = new Date(candidate.scheduledAt.getTime() - 30_000);
            const before = await loadTrainPositions(transaction, replayAt);
            await updateOffsets(transaction, matches, new Date('2026-09-04T00:30:00.000Z'));
            assert.deepEqual(await loadTrainPositions(transaction, replayAt), before, 'current offsets must not change planned replay');
            throw new Error('rollback integration test');
        }), /rollback integration test/);
    } finally {
        await database.close();
    }
});

test('schedule ensure rolls back failures, serializes concurrent callers and preserves existing offsets', {
    skip: connectionString ? false : 'DATABASE_URL is not set',
}, async () => {
    const database = createDatabase(connectionString!);
    const date = '2198-06-09';
    let ownsDate = false;
    try {
        const existing = await database.pool.query('SELECT 1 FROM mtr.train_runs WHERE service_date = $1 LIMIT 1', [date]);
        assert.equal(existing.rowCount, 0, `Integration test requires unused service date ${date}`);
        ownsDate = true;
        await assert.rejects(ensureMissingSchedule(database.pool, date, () => {
            throw new Error('generation failed');
        }), /generation failed/);
        const afterFailure = await database.pool.query('SELECT 1 FROM mtr.train_runs WHERE service_date = $1', [date]);
        assert.equal(afterFailure.rowCount, 0);
        const config = readServiceConfig();
        const results = await Promise.all([
            ensureSchedule(database.pool, config, date),
            ensureSchedule(database.pool, config, date),
        ]);
        assert.deepEqual(results.sort(), [false, true]);
        const before = await database.pool.query('SELECT id, starts_at, ends_at FROM mtr.train_runs WHERE service_date = $1 ORDER BY id', [date]);
        assert.ok(before.rows.length > 0);
        await database.pool.query('INSERT INTO mtr.realtime_offsets (train_id, delay_seconds, observed_at) VALUES ($1, 123, now())', [before.rows[0].id]);
        assert.equal(await ensureSchedule(database.pool, config, date), false);
        const after = await database.pool.query('SELECT id, starts_at, ends_at FROM mtr.train_runs WHERE service_date = $1 ORDER BY id', [date]);
        assert.deepEqual(after.rows, before.rows);
        const offset = await database.pool.query('SELECT delay_seconds FROM mtr.realtime_offsets WHERE train_id = $1', [before.rows[0].id]);
        assert.equal(offset.rows[0].delay_seconds, 123);
    } finally {
        if (ownsDate) await database.pool.query('DELETE FROM mtr.train_runs WHERE service_date = $1', [date]);
        await database.close();
    }
});

test('network import safety and schedule replacement work in one locked transaction', {
    skip: connectionString ? false : 'DATABASE_URL is not set',
}, async () => {
    const client = new Client({ connectionString });
    await client.connect();
    try {
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [NETWORK_DATA_LOCK]);

        const generatedDir = fileURLToPath(new URL('../../data/osm/generated/', import.meta.url));
        const network = readNetworkData(generatedDir);
        network.patterns[0]!.stops[1]!.distanceM += 1;
        await assert.rejects(
            importNetwork(client, network, readServiceConfig(), false),
            error => error instanceof UnsafeNetworkChangeError
                && error.patternIds.includes(network.patterns[0]!.id)
                && error.serviceDates.includes('2026-09-04'),
        );

        const schedule = await generateScheduleForDate(client, readServiceConfig(), '2099-01-01');
        assert.ok(schedule.runs.length > 0);
        assert.ok(schedule.legs.length > schedule.runs.length);
    } finally {
        await client.query('ROLLBACK').catch(() => {});
        await client.end();
    }
});
