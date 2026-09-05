import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mergeMotion, sampleMotion } from '../src/train-motion.ts';
import type { TrainPosition } from '../src/api.ts';

test('time interpolation follows the supplied bend and stops at the prediction horizon', () => {
    const train = { motion: [
        { at: 1000, lng: 0, lat: 0, bearing: 90 },
        { at: 2000, lng: 1, lat: 0, bearing: 0 },
        { at: 3000, lng: 1, lat: 1, bearing: 0 },
    ] } as TrainPosition;
    assert.equal(sampleMotion(train, 1500).lat, 0);
    assert.equal(sampleMotion(train, 2500).lng, 1);
    assert.equal(sampleMotion(train, 4000).lat, 1);
    assert.equal(sampleMotion(train, 4000).estimate, 'stale');
});

test('new trajectory replaces only the future, preserving the buffered past', () => {
    const old = [{ at: 1000, lng: 0, lat: 0, bearing: 0 }, { at: 3000, lng: 0, lat: 2, bearing: 0 }];
    const next = [{ at: 2000, lng: 0, lat: 1, bearing: 0 }, { at: 4000, lng: 0, lat: 1.5, bearing: 0 }];
    assert.deepEqual(mergeMotion(old, next).map(p => p.at), [1000, 2000, 4000]);
});
