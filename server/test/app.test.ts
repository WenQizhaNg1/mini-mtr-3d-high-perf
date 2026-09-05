import assert from 'node:assert/strict';
import test from 'node:test';
import { createApp, type AppDependencies } from '../src/app.js';
import type { TrainSnapshot } from '../src/domain/types.js';
import type { LiveTrainService, LiveSubscription } from '../src/services/live-train-service.js';

const snapshot: TrainSnapshot = {
    timestamp: '2026-09-04T00:00:00.000Z',
    trains: [],
};

function fakeLive(latestSnapshot: TrainSnapshot | null, streamSnapshots: TrainSnapshot[] = []): LiveTrainService {
    return {
        latestSnapshot,
        start() {},
        async stop() {},
        subscribe(): LiveSubscription {
            const values = [...streamSnapshots];
            return {
                async next() {
                    return values.shift() || null;
                },
                close() {},
            };
        },
    };
}

function dependencies(overrides: Partial<AppDependencies> = {}): AppDependencies {
    return {
        async checkDatabase() {},
        trains: {
            async loadSnapshot(at) {
                return { timestamp: at.toISOString(), trains: [] };
            },
        },
        liveTrains: fakeLive(null),
        network: {
            async loadCatalog() {
                return {
                    service: { serviceDayStart: '05:30:00+08:00', serviceEndOffsetMinutes: 1110 },
                    lines: [],
                    stations: [],
                };
            },
        },
        serviceDays: {
            async load() {
                return {
                    serviceDate: '2026-09-04',
                    active: true,
                    startsAt: '2026-09-03T21:30:00.000Z',
                    endsAt: '2026-09-04T16:00:00.000Z',
                    replay: { startsAt: null, endsAt: null },
                    schedules: { current: true, nextServiceDate: '2026-09-05', next: false },
                };
            },
        },
        weather: {
            async load() {
                return {
                    icon: 50,
                    temperatureC: 30,
                    humidityPercent: 75,
                    warnings: [],
                    updatedAt: '2026-09-04T08:00:00+08:00',
                    cachedAt: '2026-09-04T00:00:00.000Z',
                    stale: false,
                    error: null,
                };
            },
        },
        getRealtimeStatus: () => ({ enabled: false }),
        getOperations: () => ({ realtime: { enabled: false }, lines: [] }),
        logger: { error() {} },
        ...overrides,
    };
}

test('health checks the database and returns realtime status with CORS', async () => {
    let checks = 0;
    const app = createApp(dependencies({
        async checkDatabase() {
            checks++;
        },
        getRealtimeStatus: () => ({
            enabled: true,
            healthy: true,
            lastPollAt: '2026-09-04T00:00:00.000Z',
            lastError: null,
            updatedTrains: 3,
        }),
    }));

    const response = await app.request('/health', {
        headers: { Origin: 'http://127.0.0.1:8080' },
    });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('access-control-allow-origin'), '*');
    assert.equal(checks, 1);
    assert.deepEqual(await response.json(), {
        status: 'ok',
        realtime: {
            enabled: true,
            healthy: true,
            lastPollAt: '2026-09-04T00:00:00.000Z',
            lastError: null,
            updatedTrains: 3,
        },
    });
});

test('trains without at returns the shared live snapshot', async () => {
    const app = createApp(dependencies({
        trains: {
            async loadSnapshot() {
                throw new Error('database query should not run');
            },
        },
        liveTrains: fakeLive(snapshot),
    }));

    const response = await app.request('/api/trains');
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), snapshot);
});

test('trains validates at and loads a replay snapshot', async () => {
    let received: Date | null = null;
    const app = createApp(dependencies({
        trains: {
            async loadSnapshot(at) {
                received = at;
                return { timestamp: at.toISOString(), trains: [] };
            },
        },
    }));

    const invalid = await app.request('/api/trains?at=invalid');
    assert.equal(invalid.status, 400);
    assert.deepEqual(await invalid.json(), { error: 'Invalid at timestamp' });

    const response = await app.request('/api/trains?at=2026-09-04T08:00:00%2B08:00');
    assert.equal(response.status, 200);
    assert.equal(received?.toISOString(), '2026-09-04T00:00:00.000Z');
    assert.equal(response.headers.get('cache-control'), 'no-store');
});

test('live trains keeps the existing retry and default message protocol', async () => {
    const app = createApp(dependencies({
        liveTrains: fakeLive(snapshot, [snapshot]),
    }));

    const response = await app.request('/api/trains/live');
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type') || '', /^text\/event-stream/);
    const body = await response.text();
    assert.match(body, /^retry: 3000\n\n/);
    assert.match(body, /data: {"timestamp":"2026-09-04T00:00:00.000Z","trains":\[\]}\n\n/);
    assert.doesNotMatch(body, /event:/);
});

test('app preserves JSON 404 and 500 responses', async () => {
    const logged: unknown[] = [];
    const app = createApp(dependencies({
        async checkDatabase() {
            throw new Error('database unavailable');
        },
        logger: { error: (...values: unknown[]) => logged.push(values) },
    }));

    const missing = await app.request('/missing');
    assert.equal(missing.status, 404);
    assert.deepEqual(await missing.json(), { error: 'Not found' });

    const failed = await app.request('/health');
    assert.equal(failed.status, 500);
    assert.deepEqual(await failed.json(), { error: 'Internal server error' });
    assert.equal(logged.length, 1);
});

test('serves network, service-day, operations, and weather contracts', async () => {
    const app = createApp(dependencies());

    const network = await app.request('/api/network');
    assert.equal(network.status, 200);
    assert.deepEqual(await network.json(), {
        service: { serviceDayStart: '05:30:00+08:00', serviceEndOffsetMinutes: 1110 },
        lines: [],
        stations: [],
    });

    const serviceDay = await app.request('/api/service-day?at=2026-09-04T08:00:00%2B08:00');
    assert.equal(serviceDay.status, 200);
    assert.equal((await serviceDay.json() as { serviceDate: string }).serviceDate, '2026-09-04');
    const invalidTime = await app.request('/api/service-day?at=invalid');
    assert.equal(invalidTime.status, 400);

    const operations = await app.request('/api/operations');
    assert.deepEqual(await operations.json(), { realtime: { enabled: false }, lines: [] });

    const weather = await app.request('/api/weather?lang=en');
    assert.equal(weather.status, 200);
    assert.equal((await weather.json() as { icon: number }).icon, 50);
    const invalidLanguage = await app.request('/api/weather?lang=tc');
    assert.equal(invalidLanguage.status, 400);
});
