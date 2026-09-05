import assert from 'node:assert/strict';
import test from 'node:test';
import type { ServiceConfig } from '../src/domain/types.js';
import type { MtrClient } from '../src/integrations/mtr-client.js';
import { createRealtimeService } from '../src/services/realtime-service.js';

const serviceConfig: ServiceConfig = {
    schemaVersion: 1,
    serviceDayStart: '05:30:00+08:00',
    serviceEndOffsetMinutes: 1110,
    dwellSeconds: 30,
    travelTimeFactor: 1.35,
    lines: [{
        lineId: 'TWL',
        apiCode: 'TWL',
        monitorStation: 'MOK',
        nameEn: 'Tsuen Wan Line',
        nameZh: '荃灣綫',
        colour: '#ed1d24',
        incidentAnchorStation: 'MOK',
        speedKmph: 70,
        firstTrainOffsetMinutes: 5,
        lastTrainOffsetMinutes: 1185,
        headways: { peak: 3, normal: 5, evening: 6, late: 9 },
        patterns: {
            UP: { default: 'TWL:CEN-TSW' },
            DOWN: { default: 'TWL:TSW-CEN' },
        },
    }],
};

test('stopping realtime polling aborts an in-flight MTR request', async () => {
    let started: (() => void) | undefined;
    const requestStarted = new Promise<void>(resolve => started = resolve);
    let aborted = false;
    const mtrClient: MtrClient = {
        async getSchedule(_line, _station, signal) {
            started?.();
            return new Promise((_, reject) => {
                signal?.addEventListener('abort', () => {
                    aborted = true;
                    reject(signal.reason);
                }, { once: true });
            });
        },
    };
    const realtime = createRealtimeService(
        { observe: () => 0, save: async () => {} },
        serviceConfig,
        mtrClient,
        { warn() {} },
    );

    realtime.start();
    await requestStarted;
    realtime.stop();
    await new Promise(resolve => setTimeout(resolve, 0));

    assert.equal(aborted, true);
});

test('keeps the latest line incident in the operations snapshot', async () => {
    let requested: (() => void) | undefined;
    const requestFinished = new Promise<void>(resolve => requested = resolve);
    const mtrClient: MtrClient = {
        async getSchedule() {
            requested?.();
            return {
                status: 0,
                message: 'Special service arrangement',
                url: 'https://example.test/incident',
                isdelay: 'Y',
                data: {},
            };
        },
    };
    const realtime = createRealtimeService(
        { observe: () => 0, save: async () => {} },
        serviceConfig,
        mtrClient,
        { warn() {} },
    );

    realtime.start();
    await requestFinished;
    await new Promise(resolve => setTimeout(resolve, 0));
    realtime.stop();

    assert.equal(realtime.operations.lines[0]?.incident?.message, 'Special service arrangement');
    assert.equal(realtime.operations.lines[0]?.incident?.isDelay, true);
});
