import assert from 'node:assert/strict';
import test from 'node:test';
import { choosePattern, generateSchedule } from '../src/domain/schedule.js';
import { serviceDateAt, serviceWindowAt } from '../src/domain/time.js';
import type { ServiceConfig } from '../src/domain/types.js';

test('uses the 05:30 Hong Kong boundary for the default service date', () => {
    assert.equal(serviceDateAt(new Date('2026-09-04T20:59:00Z'), '05:30:00+08:00'), '2026-09-04');
    assert.equal(serviceDateAt(new Date('2026-09-04T21:30:00Z'), '05:30:00+08:00'), '2026-09-05');
});

test('marks the midnight-to-05:30 gap as inactive', () => {
    const window = serviceWindowAt(
        new Date('2026-09-04T18:00:00Z'),
        '05:30:00+08:00',
        1110,
    );
    assert.equal(window.serviceDate, '2026-09-04');
    assert.equal(window.active, false);
    assert.equal(window.startsAt.toISOString(), '2026-09-03T21:30:00.000Z');
    assert.equal(window.endsAt.toISOString(), '2026-09-04T16:00:00.000Z');
});

test('chooses configured alternate route patterns at a stable interval', () => {
    const patterns = { default: 'main', alternate: 'branch', every: 3 };
    assert.equal(choosePattern(patterns, 2), 'main');
    assert.equal(choosePattern(patterns, 3), 'branch');
});

test('generates runs and legs from route stops', () => {
    const config: ServiceConfig = {
        schemaVersion: 1,
        serviceDayStart: '05:30:00+08:00',
        serviceEndOffsetMinutes: 180,
        dwellSeconds: 30,
        travelTimeFactor: 1,
        lines: [{
            lineId: 'TST',
            apiCode: 'TST',
            monitorStation: 'ONE',
            nameEn: 'Test Line',
            nameZh: '測試綫',
            colour: '#123456',
            incidentAnchorStation: 'ONE',
            speedKmph: 60,
            firstTrainOffsetMinutes: 0,
            lastTrainOffsetMinutes: 2,
            headways: { peak: 60, normal: 60, evening: 60, late: 60 },
            patterns: {
                UP: { default: 'TST:ONE-TWO' },
                DOWN: { default: 'TST:TWO-ONE' },
            },
        }],
    };
    const schedule = generateSchedule(config, '2026-09-05', [
        { patternId: 'TST:ONE-TWO', stopSequence: 0, stationCode: 'ONE', distanceM: 0 },
        { patternId: 'TST:ONE-TWO', stopSequence: 1, stationCode: 'TWO', distanceM: 1_000 },
        { patternId: 'TST:TWO-ONE', stopSequence: 0, stationCode: 'TWO', distanceM: 0 },
        { patternId: 'TST:TWO-ONE', stopSequence: 1, stationCode: 'ONE', distanceM: 1_000 },
    ]);

    assert.equal(schedule.runs.length, 2);
    assert.equal(schedule.legs.length, 2);
    assert.equal(schedule.runs[0]!.id, '2026-09-05:TST:UP:001');
    assert.equal(schedule.runs[1]!.id, '2026-09-05:TST:DOWN:001');
    assert.equal(schedule.legs[0]!.arrivalAt.getTime() - schedule.legs[0]!.departureAt.getTime(), 60_000);
});

test('rejects a normalized but nonexistent calendar date', () => {
    const config: ServiceConfig = {
        schemaVersion: 1,
        serviceDayStart: '05:30:00+08:00',
        serviceEndOffsetMinutes: 60,
        dwellSeconds: 30,
        travelTimeFactor: 1,
        lines: [],
    };
    assert.throws(() => generateSchedule(config, '2026-02-31', []), /Invalid service date/);
});
