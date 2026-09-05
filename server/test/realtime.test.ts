import assert from 'node:assert/strict';
import test from 'node:test';
import { extractIncident, matchArrivals, normalizeArrivals } from '../src/domain/realtime.js';
import type { Monitor, TrainCandidate } from '../src/domain/types.js';

const monitor: Monitor = {
    lineId: 'TWL',
    apiCode: 'TWL',
    stationCode: 'MOK',
    patterns: {
        UP: { default: 'TWL:CEN-TSW' },
        DOWN: { default: 'TWL:TSW-CEN' },
    },
};

test('normalizes arrivals, sorts them, and ignores valid=N', () => {
    const arrivals = normalizeArrivals({
        data: {
            'TWL-MOK': {
                UP: [
                    { time: '2026-09-04 12:03:00', dest: 'TSW' },
                    { time: '2026-09-04 12:01:00', dest: 'TSW' },
                    { time: '2026-09-04 12:02:00', dest: 'TSW', valid: 'N' },
                    { time: 'invalid', dest: 'TSW' },
                ],
            },
        },
    }, monitor, 'UP');

    assert.deepEqual(arrivals, [
        { at: new Date('2026-09-04T04:01:00.000Z'), destination: 'TSW' },
        { at: new Date('2026-09-04T04:03:00.000Z'), destination: 'TSW' },
    ]);
});

test('matches each arrival to the nearest planned train with the same destination', () => {
    const candidates: TrainCandidate[] = [
        { id: 'main-1', destination: 'POA', scheduledAt: new Date('2026-09-04T12:00:00Z') },
        { id: 'branch-1', destination: 'LHP', scheduledAt: new Date('2026-09-04T12:01:00Z') },
        { id: 'main-2', destination: 'POA', scheduledAt: new Date('2026-09-04T12:04:00Z') },
    ];
    const arrivals = [
        { destination: 'POA', at: new Date('2026-09-04T12:04:45Z') },
        { destination: 'LHP', at: new Date('2026-09-04T12:02:30Z') },
    ];

    assert.deepEqual(matchArrivals(candidates, arrivals), [
        { trainId: 'main-2', delaySeconds: 45 },
        { trainId: 'branch-1', delaySeconds: 90 },
    ]);
});

test('an empty destination uses the nearest unused train and keeps stable ties', () => {
    const candidates: TrainCandidate[] = [
        { id: 'first', destination: 'A', scheduledAt: new Date('2026-09-04T12:00:00Z') },
        { id: 'second', destination: 'B', scheduledAt: new Date('2026-09-04T12:02:00Z') },
    ];

    assert.deepEqual(matchArrivals(candidates, [
        { destination: '', at: new Date('2026-09-04T12:01:00Z') },
    ]), [{ trainId: 'first', delaySeconds: 60 }]);
});

test('ignores arrivals without a matching destination or within-window train', () => {
    const candidates: TrainCandidate[] = [
        { id: 'main-1', destination: 'POA', scheduledAt: new Date('2026-09-04T12:00:00Z') },
    ];

    assert.deepEqual(matchArrivals(candidates, [
        { destination: 'LHP', at: new Date('2026-09-04T12:01:00Z') },
        { destination: 'POA', at: new Date('2026-09-04T12:20:00Z') },
    ]), []);
});

test('preserves early arrivals as negative delays', () => {
    const candidates: TrainCandidate[] = [
        { id: 'early', destination: 'TSW', scheduledAt: new Date('2026-09-04T12:02:00Z') },
    ];
    assert.deepEqual(matchArrivals(candidates, [
        { destination: 'TSW', at: new Date('2026-09-04T12:01:30Z') },
    ]), [{ trainId: 'early', delaySeconds: -30 }]);
});

test('extracts incidents and ignores normal end-of-service messages', () => {
    const observedAt = new Date('2026-09-04T00:00:00Z');
    assert.deepEqual(extractIncident({
        status: 0,
        message: 'Special service arrangement',
        url: 'https://example.test/incident',
        isdelay: 'Y',
    }, observedAt), {
        message: 'Special service arrangement',
        url: 'https://example.test/incident',
        isDelay: true,
        updatedAt: observedAt.toISOString(),
    });
    assert.equal(extractIncident({
        status: 0,
        message: 'Train service has ended',
    }, observedAt), null);
});
