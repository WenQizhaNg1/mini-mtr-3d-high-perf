<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { storeToRefs } from 'pinia';
import MapCanvas from '../components/map/MapCanvas.vue';
import StatusPanel from '../components/map/StatusPanel.vue';
import SelectionPopup from '../components/map/SelectionPopup.vue';
import TimelinePanel from '../components/map/TimelinePanel.vue';
import AboutDialog from '../components/map/AboutDialog.vue';
import { usePlayback } from '../composables/usePlayback';
import { useMapSession } from '../composables/useMapSession';
import { useDisplay } from '../composables/useDisplay';
import { usePreferencesStore } from '../stores/preferences';
import { useTransitStore } from '../stores/transit';
import type { TrainSnapshot, Operator, MotionMode } from '../api/types';
import { get } from '../api/client';
import type { MapSelection } from '../map/createMap';

const { language, powerSave } = storeToRefs(usePreferencesStore());
const transit = useTransitStore();
const { network, weather } = storeToRefs(transit);
const { text } = useDisplay(() => language.value);
const selection = ref<MapSelection>(null);
const selectionPoint = ref<{ x: number; y: number } | null>(null);
const about = ref(false);
const mapError = ref('');
const errors = computed(() => ({ ...transit.errors, map: mapError.value }));
const frame = shallowRef<{ snapshot: TrainSnapshot; animate: boolean }>();
const operators = ref<Operator[]>([]);
const operator = ref('');
const mode = ref<MotionMode>('simulation');
const refresh = ref(0);
const currentOperator = computed(() => operators.value.find(o => o.code === operator.value));
const timezone = computed(() => network.value?.timezone || currentOperator.value?.timezone || 'Asia/Hong_Kong');
const lifetime = new AbortController();
async function refreshInputs() {
    refresh.value++;
    try {
        const values = await get<Operator[]>('operators', lifetime.signal);
        if (lifetime.signal.aborted) return;
        operators.value = values;
        const selected = values.find(value => value.code === operator.value);
        if (!selected) operator.value = values[0]?.code || '';
        else if (!selected.modes.includes(mode.value)) mode.value = selected.mode;
    } catch (cause) { if (!lifetime.signal.aborted) mapError.value = String(cause); }
}
const { live, playing, speed, connected, loading, error, at, snapshot, service,
    rangeStart, rangeEnd, seek, goLive, toggle } = usePlayback((snapshot, animate) => {
        frame.value = { snapshot, animate };
    }, operator, mode, () => { void refreshInputs(); });
useMapSession(operator, refresh);
watch(operator, () => {
    mode.value = currentOperator.value?.mode || 'simulation';
    selection.value = null;
    goLive();
});
watch(mode, () => { selection.value = null; goLive(); });
const selectedTrain = computed(() => selection.value?.kind === 'train'
    ? snapshot.value?.trains.find(train => train.id === selection.value?.id) : undefined);
const selectedStation = computed(() => selection.value?.kind === 'station'
    ? network.value?.stations.find(station => station.code === selection.value?.id) : undefined);
watch(snapshot, value => {
    if (value && selection.value?.kind === 'train' && !selectedTrain.value) selection.value = null;
});
function escape(event: KeyboardEvent) {
    if (event.key === 'Escape') { selection.value = null; about.value = false; }
}
onMounted(async () => {
    window.addEventListener('keydown', escape);
    try {
        operators.value = await get<Operator[]>('operators', lifetime.signal);
        operator.value = operators.value.find(o => o.code === 'mtr')?.code || operators.value[0]?.code || '';
    } catch (cause) { if (!lifetime.signal.aborted) mapError.value = String(cause); }
});
onBeforeUnmount(() => { lifetime.abort(); window.removeEventListener('keydown', escape); });
</script>

<template>
    <main class="map-shell">
        <RouterLink class="workbench-link" to="/workbench">图层工作台</RouterLink>
        <div class="runtime-controls">
            <select v-model="operator" aria-label="运营方"><option v-for="item in operators" :key="item.code" :value="item.code">{{ item.name }}</option></select>
            <select v-model="mode" aria-label="运行模式"><option v-for="item in currentOperator?.modes" :key="item" :value="item">{{ item === 'simulation' ? '模拟 · 时刻表' : '实时 · 来源观测' }}</option></select>
            <span v-if="!operators.length">请先在工作台配置交通接入</span>
        </div>
        <MapCanvas :at="at" :operator="operator" :timezone="timezone" :refresh="refresh" :bounds="network?.bounds" :language="language" :power-save="powerSave" :selection="selection" :frame="frame"
            @select="selection = $event" @point="selectionPoint = $event" @error="mapError = $event" />
        <StatusPanel :language="language" :live="live" :mode="mode" :timezone="timezone" :connected="connected" :at="at"
            :network="network" :weather="weather" :snapshot="snapshot"
            :service="service" :errors="errors" :error="error" />
        <SelectionPopup :language="language" :network="network" :selected-train="selectedTrain"
            :selected-station="selectedStation" :point="selectionPoint" @close="selection = null" />
        <TimelinePanel :mode="mode" :timezone="timezone" :live="live" :playing="playing" :speed="speed" :power-save="powerSave" :language="language" :at="at"
            :start="service ? Date.parse(service.startsAt) : rangeStart" :end="service ? Date.parse(service.endsAt) : rangeEnd" :ready="!!service" :replay-available="!!service?.replay.startsAt" :loading="loading"
            @toggle="toggle" @go-live="goLive" @speed="speed = $event" @power-save="powerSave = !powerSave"
            @language="language = language === 'zh' ? 'en' : 'zh'" @seek="seek" />
        <button class="ui-button about-toggle" :class="{ active: about }" @click="about = true">{{ text('關於', 'About') }}</button>
        <AboutDialog v-if="about" :language="language" @close="about = false" />
        <section v-if="mode === 'realtime'" class="ui-panel arrivals-panel">
            <strong>到站预测</strong><p v-if="!snapshot?.arrivals?.length">{{ snapshot?.error || '暂无到站观测' }}</p>
            <p v-for="arrival in snapshot?.arrivals" :key="arrival.id">
                {{ network?.lines.find(l => l.id === arrival.lineId)?.nameZh || arrival.lineId }} ·
                {{ network?.stations.find(s => s.code === arrival.stationId)?.nameZh || arrival.stationId }} →
                {{ network?.stations.find(s => s.code === arrival.destinationId)?.nameZh || arrival.destinationId || '—' }}
                {{ new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(arrival.eta)) }}
                <small>{{ arrival.stale ? '观测已过期' : '来源到站预测' }}</small>
            </p>
        </section>
    </main>
</template>

<style scoped>
.workbench-link { position: absolute; z-index: 5; right: 52px; top: 12px; padding: 8px 12px; border-radius: 6px; background: #fff; color: #18334b; font: 13px var(--font-heiti); text-decoration: none; }
.runtime-controls { position:absolute; z-index:5; top:56px; right:52px; display:flex; gap:6px; font-size:13px; }
.runtime-controls select { padding:8px; border:1px solid #ccd5dc; border-radius:5px; background:white; color:#18334b; }
.arrivals-panel { position:absolute; z-index:4; right:16px; top:110px; width:340px; max-height:50vh; overflow:auto; padding:16px; font-size:13px; }
.arrivals-panel p { padding:8px 0; border-bottom:1px solid #ccd5dc; }.arrivals-panel small { display:block; opacity:.65; }
</style>
