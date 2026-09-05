import assert from 'node:assert/strict';
import test from 'node:test';
import { composeStyle, defaultLayer } from '../src/map/workbenchStyle.ts';
import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import type { StyleSpecification } from 'maplibre-gl';

const base: StyleSpecification = { version: 8, sources: {}, layers: [{ id: 'background', type: 'background' }] };
const catalog = { stations: { name: 'Stations', source: { type: 'vector' as const, tiles: ['http://example.test/{z}/{x}/{y}'] }, layers: [{ id: 'features', geometry: 'Point', fields: { name: 'string' } }] } };
test('composes native styles without mutating the basemap or losing expressions', () => {
    const layer = defaultLayer('points', 'stations', 'features', 'circle');
    const style = composeStyle(base, { code: 'test', name: 'Test', basemap: 'positron', layers: [layer] }, catalog);
    assert.equal(style.layers.length, 2);
    assert.equal(base.layers.length, 1);
    assert.deepEqual(base.sources, {});
    assert.deepEqual(validateStyleMin(style), []);
    assert.deepEqual(style.state, { language: { default: 'zh' } });
});
test('refuses missing sources and reports invalid native properties', () => {
    const layer = defaultLayer('points', 'missing', 'features', 'circle');
    assert.throws(() => composeStyle(base, { code: 'x', name: 'x', basemap: 'positron', layers: [layer] }, catalog), /数据源/);
    const invalid = defaultLayer('points', 'stations', 'features', 'circle');
    (invalid.paint as Record<string, unknown>)['circle-radius'] = -4;
    assert.ok(validateStyleMin(composeStyle(base, { code: 'x', name: 'x', basemap: 'positron', layers: [invalid] }, catalog)).length > 0);
});
