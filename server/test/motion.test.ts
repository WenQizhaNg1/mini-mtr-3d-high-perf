import assert from 'node:assert/strict';
import test from 'node:test';
import { MotionEngine, progress, retime, type MotionState, type RoutePath } from '../src/domain/motion.js';
import type { Monitor } from '../src/domain/types.js';

const epoch = Date.parse('2026-09-05T00:00:00Z');
const path: RoutePath = { id: 'test', lineId: 'TWL', destination: 'B', colour: '#fff',
    coordinates: [[114, 22], [114.005, 22], [114.005, 22.005]], distances: [0, 500, 1000] };
const nodes = [
    { at: epoch, distance: 0, station: 'A' },
    { at: epoch + 100_000, distance: 500, station: 'MOK' },
    { at: epoch + 130_000, distance: 500, station: 'MOK' },
    { at: epoch + 230_000, distance: 1000, station: 'B' },
];
function state(): MotionState {
    return { id: 'one', patternId: 'test', nodes: structuredClone(nodes), plan: structuredClone(nodes), conflict: false, retired: false };
}
function engine() {
    const engine = new MotionEngine(new Map([['test', path]]), new Map([['TWL', 20]]));
    engine.sync([state()], epoch);
    return engine;
}
const monitor: Monitor = { lineId: 'TWL', apiCode: 'TWL', stationCode: 'MOK',
    patterns: { UP: { default: 'test' }, DOWN: { default: 'reverse' } } };

test('ETA changes preserve the update position, past trajectory, dwell, and speed bounds', () => {
    for (const change of [-30_000, 30_000, 120_000]) {
        const s = state(), now = epoch + 20_000;
        const before = progress(s.nodes, now);
        assert.equal(retime(s, 'MOK', epoch + 100_000 + change, now, 20), true);
        assert.equal(progress(s.nodes, now), before);
        assert.equal(progress(s.nodes, now - 10_000), 50);
        const arrival = s.nodes.findIndex(n => n.station === 'MOK');
        assert.equal(s.nodes[arrival + 1]!.at - s.nodes[arrival]!.at, 30_000);
        for (let i = 1; i < s.nodes.length; i++) {
            const a = s.nodes[i - 1]!, b = s.nodes[i]!;
            assert.ok(b.distance >= a.distance);
            assert.ok((b.distance - a.distance) / ((b.at - a.at) / 1000) <= 20);
        }
    }
});

test('impossible observations do not mutate the trajectory', () => {
    const s = state(), before = structuredClone(s.nodes);
    assert.equal(retime(s, 'MOK', epoch + 21_000, epoch + 20_000, 20), false);
    assert.deepEqual(s.nodes, before);
    assert.equal(retime(s, 'A', epoch + 100_000, epoch + 20_000, 20), false);
});

test('association persists across polls and duplicate/older observations are ignored', () => {
    const e = engine();
    const first = epoch + 10_000;
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'B', at: new Date(epoch + 130_000) }], first, first), 1);
    const before = structuredClone(e.states.get('one')!.nodes);
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'B', at: new Date(epoch + 150_000) }], first, first + 1000), 0);
    assert.deepEqual(e.states.get('one')!.nodes, before);
    const now = first + 30_000, d = progress(before, now);
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'B', at: new Date(epoch + 160_000) }], now, now), 1);
    assert.equal(progress(e.states.get('one')!.nodes, now), d);
    assert.equal(e.states.get('one')!.observation?.eta, epoch + 160_000);
});

test('ambiguous close candidates and wrong destinations are not forcibly assigned', () => {
    const e = engine();
    e.sync([{ ...state(), id: 'two', nodes: nodes.map(n => ({ ...n, at: n.at + 10_000 })) }], epoch);
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'B', at: new Date(epoch + 105_000) }], epoch + 1000, epoch + 1000), 0);
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'C', at: new Date(epoch + 105_000) }], epoch + 2000, epoch + 2000), 0);
});

test('stale observations freeze progress; reconnect resumes from frozen position', () => {
    const e = engine();
    e.states.get('one')!.nodes = nodes.map(n => ({ ...n, at: epoch + (n.at - epoch) * 4 }));
    const s = e.states.get('one')!;
    s.observation = { station: 'MOK', eta: epoch + 400_000, sourceAt: epoch };
    const frozen = e.snapshot(epoch + 190_000).trains[0]!;
    assert.equal(frozen.estimate, 'stale');
    assert.deepEqual(frozen.motion!.map(p => [p.lng, p.lat]), e.snapshot(epoch + 200_000).trains[0]!.motion!.map(p => [p.lng, p.lat]));
    const before = progress(s.nodes, epoch + 180_000);
    assert.equal(retime(s, 'MOK', epoch + 450_000, epoch + 210_000, 20), true);
    s.observation = { station: 'MOK', eta: epoch + 450_000, sourceAt: epoch + 210_000 };
    assert.equal(progress(s.nodes, epoch + 210_000), before);
    assert.ok(progress(s.nodes, epoch + 211_000) > before);
});

test('trajectory payload includes a crossed bend and checkpoint restores the same future', () => {
    const e = engine();
    e.states.get('one')!.nodes = [{ at: epoch, distance: 0, station: 'A' }, { at: epoch + 100_000, distance: 1000, station: 'B' }];
    const snapshot = e.snapshot(epoch + 49_000);
    assert.ok(snapshot.trains[0]!.motion!.some(p => p.at === epoch + 50_000 && p.lng === 114.005 && p.lat === 22));
    const checkpoint = JSON.parse(JSON.stringify(e.checkpoint(epoch + 49_000)));
    const restored = engine();
    restored.restore(checkpoint, epoch + 51_000);
    assert.deepEqual(restored.snapshot(epoch + 51_000), e.snapshot(epoch + 51_000));
    assert.equal(e.snapshot(epoch + 101_000).trains.length, 0);
    e.sync([state()], epoch + 102_000);
    assert.equal(e.snapshot(epoch + 102_000).trains.length, 0);
});

test('passing the only monitor continues the corrected trip as forecast, not a timetable reset', () => {
    const e = engine();
    assert.equal(e.observe(monitor, 'UP', [{ destination: 'B', at: new Date(epoch + 150_000) }], epoch + 10_000, epoch + 10_000), 1);
    const s = e.states.get('one')!;
    const train = e.snapshot(epoch + 210_000).trains[0]!;
    assert.equal(train.estimate, 'predicted');
    assert.ok(progress(s.nodes, epoch + 211_000) > progress(s.nodes, epoch + 210_000));
    assert.notEqual(progress(s.nodes, epoch + 210_000), progress(s.plan, epoch + 210_000));
});
