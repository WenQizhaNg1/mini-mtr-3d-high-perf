<script setup lang="ts">
import { computed } from 'vue';
import type { NetworkCatalog, OperationsSnapshot, WeatherSnapshot, TrainSnapshot, ServiceDaySnapshot } from '../../api/types';
import { useDisplay } from '../../composables/useDisplay';

const props = defineProps<{
    language: 'en' | 'zh'; live: boolean; connected: boolean; at: number;
    network?: NetworkCatalog; operations?: OperationsSnapshot; weather?: WeatherSnapshot;
    snapshot?: TrainSnapshot; service?: ServiceDaySnapshot;
    errors: Record<string, string>; error: string;
}>();
const { text, name, formatTime } = useDisplay(() => props.language);
const line = (id: string) => props.network?.lines.find(item => item.id === id);
const calendarDate = computed(() => new Intl.DateTimeFormat(props.language === 'zh' ? 'zh-HK' : 'en-HK', {
    timeZone: 'Asia/Hong_Kong', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
}).format(props.at));
const apiHealthy = computed(() => props.connected && props.operations?.realtime.enabled && props.operations.realtime.healthy === true);
const incidents = computed(() => props.operations?.lines.filter(item => item.incident) || []);
const weatherIcon = computed(() => {
    const icon = props.weather?.icon || 0;
    return icon >= 60 && icon <= 65 ? '🌧' : icon >= 50 && icon <= 54 ? '🌤' : icon >= 70 && icon <= 77 ? '🌙' : '☁';
});
function safeLink(value: string | null) {
    if (!value) return undefined;
    try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined; }
    catch { return undefined; }
}
</script>

<template>
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

</template>
