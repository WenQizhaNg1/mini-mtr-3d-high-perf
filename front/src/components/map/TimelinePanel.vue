<script setup lang="ts">
import { computed, ref } from 'vue';

const props = defineProps<{
    live: boolean; playing: boolean; speed: number; powerSave: boolean; language: 'en' | 'zh';
    at: number; start: number; end: number; ready: boolean; replayAvailable: boolean; loading: boolean;
}>();
const emit = defineEmits<{
    toggle: []; goLive: []; speed: [value: number]; powerSave: []; language: []; seek: [at: number];
}>();
const text = (zh: string, en: string) => props.language === 'zh' ? zh : en;
const pct = computed(() => Math.max(0, Math.min(100, (props.at - props.start) / (props.end - props.start || 1) * 100)));
const dateInput = computed(() => new Date(props.at + 8 * 3600000).toISOString().slice(0, 19));
const dragging = ref(false);
const ticks = computed(() => {
    const span = props.end - props.start;
    if (span <= 0) return [];
    const hour = 3600000;
    const result = [];
    for (let at = Math.ceil(props.start / hour) * hour; at <= props.end; at += hour) {
        const hk = new Date(at + 8 * hour);
        const h = hk.getUTCHours();
        result.push({ at, left: (at - props.start) / span * 100,
            label: h % 3 === 0 ? `${String(h).padStart(2, '0')}:00` : '' });
    }
    return result;
});
const clock = computed(() => new Intl.DateTimeFormat(props.language === 'zh' ? 'zh-HK' : 'en-HK', {
    timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', hour12: false,
}).format(props.at));
function cycleSpeed() {
    const speeds = [1, 2, 5, 15, 60];
    emit('speed', speeds[(speeds.indexOf(props.speed) + 1) % speeds.length]);
}
function seekDate(event: Event) {
    const value = (event.target as HTMLInputElement).value;
    if (value) emit('seek', Date.parse(`${value}+08:00`));
}
</script>

<template>
    <section class="ui-panel timeline-panel" :aria-label="text('時間軸', 'Timeline')">
        <div class="tl-controls">
            <button class="ui-button tl-btn" @click="emit('toggle')" :disabled="!ready" :aria-label="playing ? text('暫停', 'Pause') : text('播放', 'Play')" :title="text('播放 / 暫停', 'Play / Pause')">{{ playing ? '⏸' : '▶' }}</button>
            <button class="ui-button tl-btn live-btn" :class="{ 'on-live': live }" @click="emit('goLive')" :aria-label="text('回到現在', 'Go live')">{{ live ? text('● 實時中', '● Live') : text('回到現在', 'Go live') }}</button>
            <button class="ui-button tl-btn speed-btn" @click="cycleSpeed" :disabled="live" :aria-label="text('回放倍速', 'Replay speed')" :title="text('回放倍速', 'Replay speed')">{{ speed }}×</button>
            <button class="ui-button tl-btn power-btn" :class="{ active: powerSave }" @click="emit('powerSave')" :aria-pressed="powerSave">{{ powerSave ? text('省電 · 開', 'Power save · ON') : text('省電', 'Power save') }}</button>
            <button class="ui-button tl-btn lang-btn" @click="emit('language')" aria-label="中文 / English">{{ language === 'zh' ? 'EN' : '中文' }}</button>
            <details class="tl-date-picker">
                <summary class="ui-button" :aria-label="text('選擇回放日期', 'Choose replay date')" :title="text('選擇回放日期', 'Choose replay date')">⋯</summary>
                <label class="ui-panel">{{ text('香港時間', 'Hong Kong time') }}<input type="datetime-local" step="1" :value="dateInput" @change="seekDate" :disabled="!replayAvailable" /></label>
            </details>
            <span v-if="loading" class="tl-loading" role="status" :aria-label="text('載入中', 'Loading')">···</span>
        </div>
        <div class="tl-draggable" :class="{ dragging }">
            <div class="tl-track">
                <div class="tl-fill" :style="{ width: `${pct}%` }" />
                <div v-for="tick in ticks" :key="tick.at" class="tl-tick" :style="{ left: `${tick.left}%` }" />
                <div class="tl-handle" :style="{ left: `${pct}%` }" />
                <div class="tl-bubble" :style="{ left: `${pct}%` }">{{ clock }}</div>
            </div>
            <input class="tl-range" type="range" :min="start" :max="end" step="1000" :value="Math.min(end, Math.max(start, at))" :disabled="!replayAvailable"
                :aria-label="text('回放時間', 'Replay time')" @input="emit('seek', Number(($event.target as HTMLInputElement).value))"
                @pointerdown="dragging = true" @pointerup="dragging = false" @pointercancel="dragging = false" @blur="dragging = false" />
        </div>
        <div class="tl-labels"><template v-for="tick in ticks" :key="tick.at"><span v-if="tick.label" class="tl-tick-label" :style="{ left: `${tick.left}%` }">{{ tick.label }}</span></template></div>
    </section>
</template>
