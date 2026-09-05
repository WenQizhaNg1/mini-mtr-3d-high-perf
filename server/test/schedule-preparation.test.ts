import assert from 'node:assert/strict';
import test from 'node:test';
import { createSchedulePreparationService } from '../src/services/schedule-preparation-service.js';

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

test('prepares current and next service dates, rolls over at 05:30, and stops', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let now = new Date('2026-09-05T05:29:59+08:00');
    const calls: string[] = [];
    const existing = new Set<string>();
    const created: string[] = [];
    const service = createSchedulePreparationService(async date => {
        calls.push(date);
        if (existing.has(date)) return false;
        existing.add(date);
        created.push(date);
        return true;
    }, '05:30:00+08:00', { info() {}, error() {} }, () => now);
    await service.start();
    assert.deepEqual(created, ['2026-09-04', '2026-09-05']);
    await service.start();
    assert.equal(calls.length, 2);
    now = new Date('2026-09-05T05:30:00+08:00');
    t.mock.timers.tick(60000);
    await flush();
    assert.deepEqual(created, ['2026-09-04', '2026-09-05', '2026-09-06']);
    await service.stop();
    t.mock.timers.tick(120000);
    assert.equal(calls.length, 4);
});

test('startup failure propagates; periodic failure logs and retries without overlapping', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let fail = true;
    let calls = 0;
    const errors: unknown[] = [];
    const service = createSchedulePreparationService(async () => {
        calls++;
        if (fail) throw new Error('database unavailable');
        return false;
    }, '05:30:00+08:00', { info() {}, error: (...args) => errors.push(args) });
    await assert.rejects(service.start(), /database unavailable/);
    t.mock.timers.tick(60000);
    assert.equal(calls, 1);
    fail = false;
    await service.start();
    fail = true;
    t.mock.timers.tick(60000);
    await flush();
    assert.equal(errors.length, 1);
    fail = false;
    t.mock.timers.tick(60000);
    await flush();
    assert.equal(calls, 6);
    await service.stop();
});

test('stop waits for in-flight preparation and does not begin the next day', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let resolve!: (created: boolean) => void;
    let calls = 0;
    const service = createSchedulePreparationService(() => {
        calls++;
        return new Promise<boolean>(done => resolve = done);
    }, '05:30:00+08:00', { info() {}, error() {} });
    const started = service.start();
    t.mock.timers.tick(180000);
    assert.equal(calls, 1);
    let stopped = false;
    const stopping = service.stop().then(() => stopped = true);
    await flush();
    assert.equal(stopped, false);
    resolve(true);
    await Promise.all([started, stopping]);
    t.mock.timers.tick(60000);
    assert.equal(calls, 1);
});
