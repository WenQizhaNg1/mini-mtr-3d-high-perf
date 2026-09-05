<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { createMtrMap, type MtrMap } from './map';
import { get, type NetworkCatalog, type OperationsSnapshot, type WeatherSnapshot } from './api';
import { usePlayback } from './playback';
import TimelinePanel from './TimelinePanel.vue';

const container = ref<HTMLElement>();
const language = ref<'en' | 'zh'>(readLanguage());
const text = (zh: string, en: string) => language.value === 'zh' ? zh : en;
const network = shallowRef<NetworkCatalog>();
const operations = shallowRef<OperationsSnapshot>();
const weather = shallowRef<WeatherSnapshot>();
const errors = ref({ network: '', operations: '', weather: '', map: '' });
const selection = ref<{ kind: 'train' | 'station'; id: string } | null>(null);
const about = ref(false);
const aboutDialog = ref<HTMLDialogElement>();
const selectionPoint = ref<{ x: number; y: number } | null>(null);
let anchorFrame = 0;
const popupStyle = computed(() => ({ left: `${selectionPoint.value?.x ?? 0}px`, top: `${selectionPoint.value?.y ?? 0}px`, visibility: selectionPoint.value ? 'visible' as const : 'hidden' as const }));
const powerSave = ref(false);
let mtrMap: MtrMap | undefined;
const { live, playing, speed, connected, loading, error, at, snapshot, service,
    rangeStart, rangeEnd, seek, goLive, toggle } = usePlayback((value, animate) => mtrMap?.setSnapshot(value, animate));
const selectedTrain = computed(() => selection.value?.kind === 'train'
    ? snapshot.value?.trains.find(train => train.id === selection.value?.id) : undefined);
const selectedStation = computed(() => selection.value?.kind === 'station'
    ? network.value?.stations.find(station => station.code === selection.value?.id) : undefined);
const name = (value?: { nameEn: string; nameZh: string }, fallback = '—') => value
    ? (language.value === 'zh' ? value.nameZh : value.nameEn) : fallback;
const stationName = (code: string | null) => name(network.value?.stations.find(station => station.code === code), code || '—');
const line = (id: string) => network.value?.lines.find(item => item.id === id);
const formatTime = (value: number | string | null, date = false) => value === null ? '—' : new Intl.DateTimeFormat(
    language.value === 'zh' ? 'zh-HK' : 'en-GB', {
        timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
        ...(date ? { year: 'numeric', month: '2-digit', day: '2-digit' } as const : {}),
    }).format(new Date(value));
const calendarDate = computed(() => new Intl.DateTimeFormat(language.value === 'zh' ? 'zh-HK' : 'en-HK', {
    timeZone: 'Asia/Hong_Kong', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
}).format(at.value));
const apiHealthy = computed(() => connected.value && operations.value?.realtime.enabled && operations.value.realtime.healthy === true);
const incidents = computed(() => operations.value?.lines.filter(item => item.incident) || []);
const weatherIcon = computed(() => {
    const icon = weather.value?.icon || 0;
    return icon >= 60 && icon <= 65 ? '🌧' : icon >= 50 && icon <= 54 ? '🌤' : icon >= 70 && icon <= 77 ? '🌙' : '☁';
});
const lifetime = new AbortController();
let weatherRequest: AbortController | undefined;
let metadataTimer: ReturnType<typeof setInterval> | undefined;
let weatherTimer: ReturnType<typeof setInterval> | undefined;

function readLanguage(): 'en' | 'zh' {
    try {
        const saved = localStorage.getItem('mtr-language');
        if (saved === 'en' || saved === 'zh') return saved;
    } catch (cause) { console.warn('Language preference unavailable', cause); }
    return navigator.language.startsWith('zh') ? 'zh' : 'en';
}
async function loadNetwork() {
    try { network.value = await get<NetworkCatalog>('network', lifetime.signal); errors.value.network = ''; }
    catch (cause) { if (!lifetime.signal.aborted) errors.value.network = String(cause); }
}
async function loadOperations() {
    try { operations.value = await get<OperationsSnapshot>('operations', lifetime.signal); errors.value.operations = ''; }
    catch (cause) { if (!lifetime.signal.aborted) errors.value.operations = String(cause); }
}
async function loadWeather() {
    weatherRequest?.abort();
    const controller = new AbortController();
    weatherRequest = controller;
    try {
        const value = await get<WeatherSnapshot>(`weather?lang=${language.value}`, controller.signal);
        if (!controller.signal.aborted) { weather.value = value; errors.value.weather = ''; }
    } catch (cause) { if (!controller.signal.aborted) errors.value.weather = String(cause); }
}
function select(value: typeof selection.value) {
    selection.value = value;
    mtrMap?.selectTrain(value?.kind === 'train' ? value.id : null);
    cancelAnimationFrame(anchorFrame);
    const follow = () => {
        selectionPoint.value = value ? mtrMap?.getSelectionPoint() || null : null;
        if (value) anchorFrame = requestAnimationFrame(follow);
    };
    follow();
}
function safeLink(value: string | null) {
    if (!value) return undefined;
    try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined; }
    catch { return undefined; }
}
function escape(event: KeyboardEvent) {
    if (event.key === 'Escape') { select(null); about.value = false; }
}
watch(language, value => {
    document.documentElement.lang = value === 'zh' ? 'zh-HK' : 'en';
    mtrMap?.setLanguage(value);
    try { localStorage.setItem('mtr-language', value); }
    catch (cause) { console.warn('Cannot save language preference', cause); }
    weather.value = undefined;
    void loadWeather();
});
watch(powerSave, value => mtrMap?.setPowerSave(value));
watch(about, async value => {
    if (value) { await nextTick(); aboutDialog.value?.showModal(); }
});
watch(snapshot, value => {
    if (value && selection.value?.kind === 'train' && !selectedTrain.value) select(null);
});
onMounted(() => {
    document.documentElement.lang = language.value === 'zh' ? 'zh-HK' : 'en';
    if (container.value) {
        mtrMap = createMtrMap(container.value, { onSelect: select, onError: message => errors.value.map = message });
        mtrMap.setLanguage(language.value);
    }
    goLive();
    void loadNetwork();
    void loadOperations();
    void loadWeather();
    metadataTimer = setInterval(() => { void loadOperations(); if (!network.value) void loadNetwork(); }, 30000);
    weatherTimer = setInterval(() => void loadWeather(), 600000);
    window.addEventListener('keydown', escape);
});
onBeforeUnmount(() => {
    lifetime.abort();
    weatherRequest?.abort();
    clearInterval(metadataTimer);
    clearInterval(weatherTimer);
    window.removeEventListener('keydown', escape);
    cancelAnimationFrame(anchorFrame);
    mtrMap?.destroy();
});
</script>

<template>
    <main class="map-shell">
        <div ref="container" class="map" :aria-label="text('港鐵地圖', 'MTR map')" />
        <section class="ui-panel clock-panel">
            <div class="clock-top">
                <span class="live-badge" :class="{ replay: !live || !connected }" />
                <span class="live-label">{{ live ? text('實時', 'LIVE') : text('回放', 'Replay') }}</span>
                <span class="tz-label">{{ text('香港時間 HKT', 'Hong Kong Time HKT') }}</span>
            </div>
            <div class="clock-time">{{ formatTime(at) }}</div>
            <div class="clock-date">{{ calendarDate }}</div>
            <div class="weather-row">{{ weather ? `${weatherIcon}  ${weather.temperatureC}°C  ${text('濕度', 'Humidity')} ${weather.humidityPercent}%` : errors.weather ? text('天氣數據暫不可用', 'Weather unavailable') : text('天氣載入中…', 'Loading weather…') }}</div>
            <div v-if="weather?.warnings.length || weather?.stale" class="weather-warnings">
                <span v-for="warning in weather?.warnings" :key="warning" class="warn-chip">{{ warning }}</span>
                <span v-if="weather?.stale" class="warn-chip" :title="weather.error || ''">{{ text('天氣更新失敗，顯示上次資料', 'Weather update failed; showing cached data') }}</span>
            </div>
            <details class="operations-details">
                <summary class="api-status" :class="{ offline: live && !apiHealthy }" :title="text('目前天氣與營運', 'Current weather & operations')">
                {{ !live ? text('⏸ 回放模式 · 按計劃時刻表運行', '⏸ Replay · planned timetable') : !connected ? text('⚠ 列車快照連線中斷', '⚠ Train feed disconnected') : apiHealthy ? text('✓ 已連接港鐵實時數據 (data.gov.hk)', '✓ Connected to MTR live data (data.gov.hk)') : text('⚠ 實時數據未就緒 · 顯示計劃時刻', '⚠ Live data unavailable · showing planned times') }}
                </summary>
                <p>{{ snapshot?.trains.length ?? 0 }} {{ text('列車', 'trains') }} · {{ text('目前天氣與營運', 'Current weather & operations') }}</p>
                <p v-if="!live" class="muted">{{ text('以下為目前資訊，並非回放時刻的歷史紀錄。', 'Current information, not historical replay data.') }}</p>
                <p v-if="weather" class="muted">{{ text('天氣更新', 'Weather updated') }} {{ formatTime(weather.updatedAt, true) }}</p>
                <p v-if="operations">{{ text('營運資料', 'Operations data') }}:
                    {{ !operations.realtime.enabled ? text('未啟用', 'Disabled') : operations.realtime.healthy === true ? text('正常', 'Healthy') : operations.realtime.healthy === false ? text('更新失敗', 'Update failed') : text('等待更新', 'Waiting for update') }}</p>
                <template v-if="operations?.realtime.enabled">
                    <p class="muted">{{ text('最近輪詢', 'Last poll') }} {{ formatTime(operations.realtime.lastPollAt, true) }}</p>
                    <p v-if="operations.realtime.lastError" class="warning">{{ operations.realtime.lastError }}</p>
                </template>
                <p v-if="operations && !incidents.length">{{ text('暫無已報告事件', 'No reported incidents') }}</p>
                <article v-for="item in incidents" :key="item.lineId" class="alert-card incident" :style="{ borderColor: line(item.lineId)?.colour }">
                    <div class="alert-card-title">{{ name(line(item.lineId), item.lineId) }}</div><div class="alert-card-msg">{{ item.incident!.message }}</div>
                    <small class="alert-card-time">{{ formatTime(item.incident!.updatedAt, true) }}</small>
                    <a class="alert-card-link" v-if="safeLink(item.incident!.url)" :href="safeLink(item.incident!.url)" target="_blank" rel="noopener noreferrer">{{ text('官方資訊', 'Official information') }}</a>
                </article>
            </details>

            <p v-if="service && !service.schedules.current" class="warning">{{ text('此服務日尚無班次資料，請選擇可用回放日期。', 'No schedules for this service day. Select an available replay date.') }}</p>
            <p v-else-if="service && !service.active" class="muted">{{ text('目前不在服務時段。', 'Outside service hours.') }}</p>
            <p v-for="(message, key) in errors" v-show="message" :key="key" class="warning" role="status">{{ message }}</p>
            <p v-if="error" class="warning" role="status">{{ error }}</p>
        </section>

        <section v-if="selectedTrain" class="train-popup selection" :style="{ ...popupStyle, '--line-color': selectedTrain.colour }">
            <div class="train-popup-bar" />
            <div class="train-popup-body">
                <div class="train-popup-line">{{ name(line(selectedTrain.lineId), selectedTrain.lineId) }}</div>
                <div class="train-popup-dir">{{ text('往', 'to ') }}{{ stationName(selectedTrain.destinationStation) }}</div>
                <div class="train-popup-stop">{{ selectedTrain.state === 'dwell' ? text('目前站', 'Current') : text('上一站', 'Prev') }}: {{ stationName(selectedTrain.previousStation) }} {{ formatTime(selectedTrain.previousTime).slice(0, 5) }}</div>
                <div class="train-popup-stop">{{ text('下一站', 'Next') }}: {{ selectedTrain.nextStation ? stationName(selectedTrain.nextStation) : text('終點', 'Terminus') }} {{ selectedTrain.nextTime ? formatTime(selectedTrain.nextTime).slice(0, 5) : '' }}</div>
                <details class="train-extra"><summary>{{ text('詳細資料', 'Details') }}</summary>
                    <div>{{ selectedTrain.state === 'dwell' ? text('停站中', 'At station') : text('行駛中', 'Running') }} · {{ Math.round(selectedTrain.delaySeconds) }} {{ text('秒偏移', 'seconds offset') }}</div>
                    <div v-if="selectedTrain.estimate">{{ text('位置推估', 'Position estimate') }} · {{ ({ planned: text('計劃推演', 'Planned'), observed: text('ETA 校準', 'ETA calibrated'), predicted: text('通過監測站後的行程預測', 'Trip forecast beyond monitor'), stale: text('觀測過期，已停止預測', 'Observation stale; prediction stopped'), conflict: text('ETA 衝突，未套用校正', 'ETA conflict; correction rejected') })[selectedTrain.estimate] }}</div>
                    <small>{{ selectedTrain.id }}</small>
                </details>
            </div>
            <button class="popup-close" @click="select(null)" :aria-label="text('關閉', 'Close')">×</button>
        </section>
        <section v-else-if="selectedStation" class="station-popup selection" :style="popupStyle">
            <div class="station-popup-name">{{ name(selectedStation) }}</div>
            <div class="station-popup-lines"><div v-for="id in selectedStation.lineIds" :key="id" class="station-popup-line-row"><span class="station-popup-swatch" :style="{ background: line(id)?.colour }" />{{ name(line(id), id) }}</div></div>
            <button class="popup-close" @click="select(null)" :aria-label="text('關閉', 'Close')">×</button>
        </section>

        <TimelinePanel :live="live" :playing="playing" :speed="speed" :power-save="powerSave" :language="language" :at="at"
            :start="service ? Date.parse(service.startsAt) : rangeStart" :end="service ? Date.parse(service.endsAt) : rangeEnd" :ready="!!service" :replay-available="!!service?.replay.startsAt" :loading="loading"
            @toggle="toggle" @go-live="goLive" @speed="speed = $event" @power-save="powerSave = !powerSave"
            @language="language = language === 'zh' ? 'en' : 'zh'" @seek="seek" />
        <button class="ui-button about-toggle" :class="{ active: about }" @click="about = true">{{ text('關於', 'About') }}</button>
        <dialog v-if="about" ref="aboutDialog" class="about-overlay" @click.self="about = false" @cancel="about = false" :aria-label="text('關於 mini mtr', 'About mini mtr')">
            <section class="ui-panel about-card">
                <button class="about-close" @click="about = false" :aria-label="text('關閉', 'Close')" autofocus>×</button>
                <h3 class="about-title">{{ text('關於 mini mtr', 'About mini mtr') }}</h3>
                <p class="about-byline">{{ text('原作者 ', 'Original author ') }}<a href="https://github.com/7gugu" target="_blank" rel="noopener noreferrer">7gugu</a></p>
                <p class="about-contact">{{ text('博客', 'Blog') }}: <a href="https://7gugu.com" target="_blank" rel="noopener noreferrer">7gugu.com</a> · {{ text('郵箱', 'Email') }}: <a href="mailto:gz7gugu@qq.com">gz7gugu@qq.com</a></p>
                <div class="about-body">
                    <p>{{ text('很早以前我就接觸到 ', 'I discovered ') }}<a href="https://minitokyo3d.com/" target="_blank" rel="noopener noreferrer">Mini Tokyo 3D</a>{{ text('。那是第一次看見整座城市的軌道交通在三維地圖上自己跑起來，當時確實被震撼到了。', ' years ago. Seeing an entire city’s rail network move on a 3D map for the first time was genuinely stunning.') }}</p>
                    <p>{{ text('那之後一直想：國內的軌交網絡能不能也做成這樣。技術門檻擺在那裡，構想停了很久。現在有了 AI 的幫助，我終於有能力把香港港鐵做成這套 3D 可視化 —— 這就是 mini mtr。', 'I kept wondering whether rail networks closer to home could look like that too. The technical bar stayed high for a long time. With AI’s help, I finally built this Hong Kong MTR 3D visualization — mini mtr.') }}</p>
                    <p>{{ text('時刻表目前按公開班距與服務時段生成，難免和真實運行有出入。如果你手上有更準確的時間，非常歡迎告訴我，我樂意修正。', 'Timetables are generated from published headways and service windows, so they may differ from real operations. If you have more accurate times, please share them — I’m happy to fix things.') }}</p>
                </div>
            </section>
        </dialog>
    </main>
</template>
