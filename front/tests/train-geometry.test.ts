import assert from 'node:assert/strict';
import { test } from 'node:test';
import { trainFeatures } from '../src/train-geometry.ts';
import type { TrainPosition } from '../src/api.ts';

const train: TrainPosition = {
    id: 'test', lineId: 'ISL', patternId: 'ISL-UP', colour: '#007dc5',
    lng: 114.16, lat: 22.28, bearing: 0, state: 'running',
    previousStation: 'ADM', nextStation: 'WAC', destinationStation: 'CHW',
    delaySeconds: 0, previousTime: '', nextTime: null,
};

test('train polygons preserve identity, close their ring and follow cardinal bearings', () => {
    for (const bearing of [0, 90, 180, 270, 359]) {
        const feature = trainFeatures([{ ...train, bearing }], 14).features[0];
        const ring = feature.geometry.coordinates[0];
        assert.equal(feature.id, train.id);
        assert.equal(feature.properties.colour, train.colour);
        assert.equal(ring.length, 5);
        assert.deepEqual(ring[0], ring[4]);
        assert.ok(feature.properties.height > 0);
        assert.ok(ring.flat().every(Number.isFinite));
        const back = [(ring[0][0] + ring[1][0]) / 2, (ring[0][1] + ring[1][1]) / 2];
        const front = [(ring[2][0] + ring[3][0]) / 2, (ring[2][1] + ring[3][1]) / 2];
        if (bearing === 0) assert.ok(front[1] > back[1]);
        if (bearing === 90) assert.ok(front[0] > back[0]);
        if (bearing === 180) assert.ok(front[1] < back[1]);
        if (bearing === 270) assert.ok(front[0] < back[0]);
    }
});

test('screen width and height grow continuously with zoom, including fractional zooms', () => {
    const feature = (zoom: number) => trainFeatures([train], zoom).features[0];
    const screenWidth = (zoom: number) => {
        const ring = feature(zoom).geometry.coordinates[0];
        return (ring[1][0] - ring[0][0]) / 360 * 512 * 2 ** zoom;
    };
    assert.ok(Math.abs(screenWidth(14) - 10) < 1e-6);
    for (const zoom of [8, 10, 11, 13.5, 14, 16, 18, 20]) {
        assert.ok(Math.abs(screenWidth(zoom + 1) / screenWidth(zoom) - Math.SQRT2) < 1e-6);
        const heightRatio = feature(zoom + 1).properties.height * 2 / feature(zoom).properties.height;
        assert.ok(Math.abs(heightRatio - Math.SQRT2) < 1e-10);
    }
    assert.deepEqual(trainFeatures([], 14).features, []);
});
