<script setup lang="ts">
import type { NetworkCatalog, WeatherSnapshot, TrainSnapshot, ServiceDaySnapshot, MotionMode } from '../../api/types';
import { useDisplay } from '../../composables/useDisplay';
const props = defineProps<{
    language: 'en' | 'zh'; live: boolean; mode: MotionMode; timezone: string; connected: boolean; at: number;
    network?: NetworkCatalog; weather?: WeatherSnapshot;
    snapshot?: TrainSnapshot; service?: ServiceDaySnapshot; errors: Record<string,string>; error: string;
}>();
const { text, formatTime } = useDisplay(() => props.language, () => props.timezone);
</script>
<template>
    <section class="ui-panel clock-panel">
        <div class="clock-top"><span class="live-badge" :class="{replay: !connected}" />
            <span class="live-label">{{ mode === 'simulation' ? text('模拟','Simulation') : text('实时观测','Realtime') }} · {{ live ? text('当前时间','Now') : text('回放','Replay') }}</span>
            <span class="tz-label">{{ timezone }}</span>
        </div>
        <div class="clock-time">{{ formatTime(at) }}</div>
        <div class="clock-date">{{ new Intl.DateTimeFormat(language === 'zh' ? 'zh-CN' : 'en-GB', {timeZone:timezone,dateStyle:'full'}).format(at) }}</div>
        <div v-if="network?.operator === 'mtr'" class="weather-row">{{ weather ? weather.temperatureC + '°C · ' + text('湿度','Humidity') + ' ' + weather.humidityPercent + '%' : text('暂无天气数据','Weather unavailable') }}</div>
        <p>{{ snapshot?.trains.length || 0 }} {{ text('辆车','vehicles') }} · {{ connected ? text('已连接','Connected') : text('连接中断','Disconnected') }}</p>
        <p v-if="mode === 'simulation'" class="muted">{{ text('按时刻表或发车规则运行','Running from timetable or departure rules') }}</p>
        <p v-else class="muted">{{ ({ok:'来源正常',empty:'暂无观测',unavailable:'来源暂不可用',stale:'观测已过期'})[snapshot?.status || 'empty'] }} · {{ snapshot?.arrivals?.length || 0 }} 条到站预测</p>
        <p v-if="snapshot?.error" class="warning">{{ snapshot.error }}</p>
        <p v-for="(message,key) in errors" v-show="message" :key="key" class="warning">{{ message }}</p>
        <p v-if="error" class="warning" role="status">{{ error }}</p>
    </section>
</template>
