import { computed, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue';
import { API_URL } from '../api/client.ts';
import { getReplayTrains, getReplayServiceDay, getServiceDay } from '../api/transit.ts';
import type { MotionMode, ServiceDaySnapshot, TrainSnapshot } from '../api/types.ts';

export function usePlayback(onSnapshot: (snapshot: TrainSnapshot, animate: boolean) => void,
    operator: Ref<string>, mode: Ref<MotionMode>, onChanged: () => void) {
    const live = ref(true);
    const playing = ref(true);
    const speed = ref(1);
    const connected = ref(false);
    const loading = ref(false);
    const error = ref('');
    const at = ref(Date.now());
    const snapshot = shallowRef<TrainSnapshot>();
    const service = shallowRef<ServiceDaySnapshot>();
    const rangeStart = computed(() => Date.parse(service.value?.replay.startsAt || service.value?.startsAt || new Date(at.value).toISOString()));
    const rangeEnd = computed(() => Date.parse(service.value?.replay.endsAt || service.value?.endsAt || new Date(at.value).toISOString()));
    let stream: EventSource | undefined;
    let changes: EventSource | undefined;
    let request: AbortController | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let generation = 0;
    let disposed = false;

    function stop() {
        generation++;
        stream?.close();
        stream = undefined;
        request?.abort();
        clearTimeout(timer);
        loading.value = false;
        connected.value = false;
    }

    function accept(value: TrainSnapshot, animate: boolean) {
        if (!Array.isArray(value.trains) || !Number.isFinite(Date.parse(value.timestamp))) {
            throw new Error('Invalid train snapshot');
        }
        snapshot.value = value;
        at.value = Date.parse(value.timestamp);
        connected.value = true;
        error.value = '';
        onSnapshot(value, animate);
    }

    async function replay(time: number, animate = false) {
        const startedAt = performance.now();
        const version = generation;
        const controller = new AbortController();
        request = controller;
        loading.value = true;
        try {
            const iso = new Date(time).toISOString();
            const [value, day] = await Promise.all([
                getReplayTrains(iso, controller.signal, operator.value),
                getReplayServiceDay(iso, controller.signal, operator.value),
            ]);
            if (disposed || version !== generation) return;
            service.value = day;
            accept(value, animate);
            if (time >= rangeEnd.value) playing.value = false;
            if (playing.value) timer = setTimeout(() => {
                void replay(Math.min(rangeEnd.value, at.value + Math.max(1000, performance.now() - startedAt) * speed.value), true);
            }, Math.max(0, 1000 - (performance.now() - startedAt)));
        } catch (cause) {
            if (version !== generation || controller.signal.aborted) return;
            error.value = String(cause);
            connected.value = false;
            playing.value = false;
        } finally {
            if (version === generation) loading.value = false;
        }
    }

    function seek(time: number) {
        if (mode.value !== 'simulation') return;
        if (!Number.isFinite(time)) return;
        stop();
        live.value = false;
        at.value = time;
        // Remove the old instant while a seek is in flight.
        snapshot.value = undefined;
        onSnapshot({ timestamp: new Date(at.value).toISOString(), trains: [] }, false);
        void replay(at.value);
    }

    async function refreshService(version: number) {
        try {
            const day = await getServiceDay(request?.signal, operator.value);
            if (disposed || version !== generation) return;
            service.value = day;
        } catch (cause) {
            if (version === generation && !disposed) error.value = String(cause);
        }
        if (!disposed && version === generation) timer = setTimeout(() => void refreshService(version), 60000);
    }

    function goLive() {
        stop();
        service.value = undefined;
        live.value = playing.value = true;
        at.value = Date.now();
        snapshot.value = undefined;
        onSnapshot({ timestamp: new Date(at.value).toISOString(), trains: [] }, false);
        if (!operator.value) return;
        request = new AbortController();
        const version = generation;
        let lastTimestamp = -Infinity;
        void refreshService(version);
        stream = new EventSource(`${API_URL}/api/trains/live?operator=${encodeURIComponent(operator.value)}&mode=${mode.value}`);
        stream.addEventListener('source-error', event => {
            if (version !== generation) return;
            connected.value = false;
            error.value = JSON.parse((event as MessageEvent).data).error;
        });
        stream.onerror = () => { connected.value = false; };
        stream.onmessage = event => {
            if (version !== generation) return;
            try {
                const value = JSON.parse(event.data) as TrainSnapshot;
                const timestamp = Date.parse(value.timestamp);
                if (timestamp <= lastTimestamp) return;
                // Beyond the supplied prediction window there is no known path
                // through the outage. Resynchronize instead of inventing a trip.
                accept(value, timestamp - lastTimestamp <= 3000);
                lastTimestamp = timestamp;
            }
            catch (cause) { error.value = String(cause); connected.value = false; }
        };
    }

    function toggle() {
        if (mode.value !== 'simulation') return;
        if (live.value) {
            playing.value = false;
            stop();
            live.value = false;
            void replay(at.value);
        } else {
            playing.value = !playing.value;
            stop();
            void replay(at.value);
        }
    }

    watch(operator, value => {
        changes?.close();
        if (!value) return;
        changes = new EventSource(`${API_URL}/api/transit/events?operator=${encodeURIComponent(value)}`);
        changes.addEventListener('data-changed', () => {
            if (operator.value !== value || disposed) return;
            onChanged();
            if (!live.value) { stop(); void replay(at.value); }
        });
    });
    onBeforeUnmount(() => { disposed = true; stop(); changes?.close(); });
    return { live, playing, speed, connected, loading, error, at, snapshot, service,
        rangeStart, rangeEnd, seek, goLive, toggle };
}
