<script setup lang="ts">
import { computed } from 'vue';
import type { NetworkCatalog, TrainPosition } from '../../api/types';
import { useDisplay } from '../../composables/useDisplay';

const props = defineProps<{
    language: 'en' | 'zh'; network?: NetworkCatalog; selectedTrain?: TrainPosition;
    selectedStation?: NetworkCatalog['stations'][number]; point: { x: number; y: number } | null;
}>();
const emit = defineEmits<{ close: [] }>();
const { text, name, formatTime } = useDisplay(() => props.language, () => props.network?.timezone || 'Asia/Hong_Kong');
const line = (id: string) => props.network?.lines.find(item => item.id === id);
const stationName = (code: string | null) => name(props.network?.stations.find(station => station.code === code), code || '—');
const popupStyle = computed(() => ({
    left: `${props.point?.x ?? 0}px`, top: `${props.point?.y ?? 0}px`,
    visibility: props.point ? 'visible' as const : 'hidden' as const,
}));
</script>

<template>
    <section v-if="selectedTrain" class="train-popup selection" :style="{ ...popupStyle, '--line-color': selectedTrain.colour }">
        <div class="train-popup-bar" />
        <div class="train-popup-body">
            <div class="train-popup-line">{{ name(line(selectedTrain.lineId), selectedTrain.lineId) }}</div>
            <div class="train-popup-dir">{{ text('往', 'to ') }}{{ stationName(selectedTrain.destinationStation) }}</div>
            <template v-if="selectedTrain.state !== 'unknown'">
                <div class="train-popup-stop">{{ selectedTrain.state === 'dwell' ? text('目前站', 'Current') : text('上一站', 'Prev') }}: {{ stationName(selectedTrain.previousStation) }} {{ formatTime(selectedTrain.previousTime).slice(0, 5) }}</div>
                <div class="train-popup-stop">{{ text('下一站', 'Next') }}: {{ selectedTrain.nextStation ? stationName(selectedTrain.nextStation) : text('終點', 'Terminus') }} {{ selectedTrain.nextTime ? formatTime(selectedTrain.nextTime).slice(0, 5) : '' }}</div>
            </template>
            <div v-if="selectedTrain.observedAt">观测时间 {{ formatTime(selectedTrain.observedAt) }}</div>
            <details class="train-extra"><summary>{{ text('詳細資料', 'Details') }}</summary>
                <div v-if="selectedTrain.estimate">{{ text('位置依据', 'Position basis') }} · {{ ({ planned: '时刻表', simulated: '模拟计划', observed: '来源观测', predicted: '估算', stale: '观测已过期', conflict: '观测冲突' })[selectedTrain.estimate] }}</div>
                <small>{{ selectedTrain.id }}</small>
            </details>
        </div>
        <button class="popup-close" @click="emit('close')" :aria-label="text('關閉', 'Close')">×</button>
    </section>
    <section v-else-if="selectedStation" class="station-popup selection" :style="popupStyle">
        <div class="station-popup-name">{{ name(selectedStation) }}</div>
        <div class="station-popup-lines"><div v-for="id in selectedStation.lineIds" :key="id" class="station-popup-line-row"><span class="station-popup-swatch" :style="{ background: line(id)?.colour }" />{{ name(line(id), id) }}</div></div>
        <button class="popup-close" @click="emit('close')" :aria-label="text('關閉', 'Close')">×</button>
    </section>

</template>
