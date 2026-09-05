import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { findChangedPatternIds, type NetworkState } from '../src/domain/network.js';
import { readNetworkData } from '../src/integrations/osm-files.js';

const base: NetworkState = {
    patternIds: ['A'],
    stops: [
        { patternId: 'A', stopSequence: 0, stationCode: 'ONE', distanceM: 0, fraction: 0 },
        { patternId: 'A', stopSequence: 1, stationCode: 'TWO', distanceM: 100, fraction: 1 },
    ],
};

test('detects route-stop semantic changes and added or removed patterns', () => {
    const changedStation = structuredClone(base);
    changedStation.stops[1]!.stationCode = 'THREE';
    assert.deepEqual(findChangedPatternIds(base, changedStation), ['A']);

    const added: NetworkState = {
        patternIds: ['A', 'B'],
        stops: [...base.stops, {
            patternId: 'B', stopSequence: 0, stationCode: 'ONE', distanceM: 0, fraction: 0,
        }],
    };
    assert.deepEqual(findChangedPatternIds(base, added), ['B']);
    assert.deepEqual(findChangedPatternIds(added, base), ['B']);
});

test('ignores insignificant floating-point differences in route stops', () => {
    const equivalent = structuredClone(base);
    equivalent.stops[1]!.distanceM += 0.0001;
    equivalent.stops[1]!.fraction -= 1e-10;
    assert.deepEqual(findChangedPatternIds(base, equivalent), []);
});

test('loads and validates the checked-in normalized OSM data', () => {
    const generatedDir = fileURLToPath(new URL('../../data/osm/generated/', import.meta.url));
    const network = readNetworkData(generatedDir);
    assert.equal(network.patterns.length, 24);
    assert.equal(network.stations.length, 98);
    assert.equal(
        network.patterns.reduce((count, pattern) => count + pattern.stops.length, 0),
        276,
    );
});
