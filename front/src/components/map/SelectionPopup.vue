<script setup lang="ts">
import { computed } from 'vue';
import type { NetworkCatalog, TrainPosition } from '../../api/types';
import { useDisplay } from '../../composables/useDisplay';

const props = defineProps<{
    language: 'en' | 'zh'; network?: NetworkCatalog; selectedTrain?: TrainPosition;
    selectedStation?: NetworkCatalog['stations'][number]; point: { x: number; y: number } | null;
}>();
const emit = defineEmits<{ close: [] }>();
const { text, name, formatTime } = useDisplay(() => props.language);
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
            <div class="train-popup-stop">{{ selectedTrain.state === 'dwell' ? text('目前站', 'Current') : text('上一站', 'Prev') }}: {{ stationName(selectedTrain.previousStation) }} {{ formatTime(selectedTrain.previousTime).slice(0, 5) }}</div>
            <div class="train-popup-stop">{{ text('下一站', 'Next') }}: {{ selectedTrain.nextStation ? stationName(selectedTrain.nextStation) : text('終點', 'Terminus') }} {{ selectedTrain.nextTime ? formatTime(selectedTrain.nextTime).slice(0, 5) : '' }}</div>
            <details class="train-extra"><summary>{{ text('詳細資料', 'Details') }}</summary>
                <div>{{ selectedTrain.state === 'dwell' ? text('停站中', 'At station') : text('行駛中', 'Running') }} · {{ Math.round(selectedTrain.delaySeconds) }} {{ text('秒偏移', 'seconds offset') }}</div>
                <div v-if="selectedTrain.estimate">{{ text('位置推估', 'Position estimate') }} · {{ ({ planned: text('計劃推演', 'Planned'), observed: text('ETA 校準', 'ETA calibrated'), predicted: text('通過監測站後的行程預測', 'Trip forecast beyond monitor'), stale: text('觀測過期，已停止預測', 'Observation stale; prediction stopped'), conflict: text('ETA 衝突，未套用校正', 'ETA conflict; correction rejected') })[selectedTrain.estimate] }}</div>
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
