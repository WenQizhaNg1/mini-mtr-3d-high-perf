import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRenderer } from 'vue';
import { usePlayback } from '../src/playback.ts';

const start = Date.parse('2026-09-04T00:00:00Z');
const day = { startsAt: new Date(start).toISOString(), endsAt: new Date(start + 100000).toISOString(),
    replay: { startsAt: new Date(start).toISOString(), endsAt: new Date(start + 100000).toISOString() } };
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

function mount() {
    const renderer = createRenderer<any, any>({
        createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
        insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
        parentNode: () => null, nextSibling: () => null,
    });
    let state!: ReturnType<typeof usePlayback>;
    const received: Array<{ timestamp: string; animate: boolean }> = [];
    const app = renderer.createApp({ setup() {
        state = usePlayback((value, animate) => received.push({ timestamp: value.timestamp, animate }));
        return () => null;
    } });
    app.mount({});
    return { state, received, unmount: () => app.unmount() };
}

test('seek ignores stale responses; live closes replay and unmount closes SSE', async t => {
    const requests: Array<{ url: URL; resolve: (value: any) => void; signal: AbortSignal }> = [];
    t.mock.method(globalThis, 'fetch', (url: string, options: any) => new Promise(resolve => {
        requests.push({ url: new URL(url), signal: options.signal, resolve: body => resolve({ ok: true, json: async () => body }) });
    }));
    class Stream {
        static all: Stream[] = [];
        closed = false;
        onerror?: () => void;
        onmessage?: (event: { data: string }) => void;
        constructor() { Stream.all.push(this); }
        close() { this.closed = true; }
    }
    const previous = Object.getOwnPropertyDescriptor(globalThis, 'EventSource');
    Object.defineProperty(globalThis, 'EventSource', { value: Stream, configurable: true });
    t.after(() => {
        if (previous) Object.defineProperty(globalThis, 'EventSource', previous);
        else Reflect.deleteProperty(globalThis, 'EventSource');
    });
    const { state, received, unmount } = mount();
    t.after(() => { if (!Stream.all.at(-1)?.closed) unmount(); });
    state.service.value = day as any;
    state.playing.value = false;
    state.seek(start + 1000);
    state.seek(start + 2000);
    assert.equal(requests[0].signal.aborted, true);
    requests[2].resolve({ timestamp: new Date(start + 2000).toISOString(), trains: [] });
    requests[3].resolve(day);
    await flush();
    requests[0].resolve({ timestamp: new Date(start + 1000).toISOString(), trains: [] });
    requests[1].resolve(day);
    await flush();
    assert.equal(state.at.value, start + 2000);
    assert.equal(received.at(-1)?.animate, false);
    state.goLive();
    requests[4].resolve(day);
    Stream.all[0].onmessage?.({ data: JSON.stringify({ timestamp: new Date(start + 9000).toISOString(), trains: [] }) });
    await flush();
    assert.equal(state.at.value, start + 9000);
    assert.equal(state.connected.value, true);
    Stream.all[0].onmessage?.({ data: JSON.stringify({ timestamp: new Date(start + 8000).toISOString(), trains: [] }) });
    assert.equal(state.at.value, start + 9000, 'older live snapshots must not move the playback clock backwards');
    state.seek(start + 3000);
    assert.equal(Stream.all[0].closed, true);
    state.goLive();
    unmount();
    assert.equal(Stream.all[1].closed, true);
    assert.equal(requests.at(-1)?.signal.aborted, true);
});

test('paused replay stays fixed; speed advances and end stops; errors remain visible', async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    let fail = false;
    t.mock.method(globalThis, 'fetch', async (raw: string) => {
        const url = new URL(raw);
        if (fail) return { ok: false, status: 500, text: async () => 'query failed' };
        return { ok: true, json: async () => url.pathname.endsWith('/trains')
            ? { timestamp: url.searchParams.get('at'), trains: [] } : day };
    });
    const { state, unmount } = mount();
    t.after(unmount);
    state.service.value = day as any;
    state.playing.value = false;
    state.seek(start);
    await flush();
    t.mock.timers.tick(5000);
    assert.equal(state.at.value, start);
    state.speed.value = 60;
    state.toggle();
    await flush();
    t.mock.timers.tick(1000);
    await flush();
    assert.ok(state.at.value >= start + 60000);
    t.mock.timers.tick(1000);
    await flush();
    assert.equal(state.at.value, start + 100000);
    assert.equal(state.playing.value, false);
    fail = true;
    state.seek(start);
    await flush();
    assert.match(state.error.value, /500.*query failed/);
    assert.equal(state.loading.value, false);
    assert.equal(state.connected.value, false);
});
