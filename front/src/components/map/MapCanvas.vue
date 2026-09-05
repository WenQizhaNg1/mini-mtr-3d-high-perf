<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createMtrMap, type MtrMap, type MapSelection } from '../../map/createMap';
import type { TrainSnapshot } from '../../api/types';

const props = defineProps<{
    language: 'en' | 'zh'; powerSave: boolean; selection: MapSelection;
    frame?: { snapshot: TrainSnapshot; animate: boolean };
}>();
const emit = defineEmits<{
    select: [value: MapSelection]; error: [message: string]; point: [value: { x: number; y: number } | null];
}>();
const container = ref<HTMLElement>();
let map: MtrMap | undefined;
let anchorFrame = 0;

function followSelection() {
    cancelAnimationFrame(anchorFrame);
    map?.selectTrain(props.selection?.kind === 'train' ? props.selection.id : null);
    const follow = () => {
        emit('point', props.selection ? map?.getSelectionPoint() || null : null);
        if (props.selection) anchorFrame = requestAnimationFrame(follow);
    };
    follow();
}
watch(() => props.selection, followSelection);
watch(() => props.language, value => map?.setLanguage(value));
watch(() => props.powerSave, value => map?.setPowerSave(value));
watch(() => props.frame, value => { if (value) map?.setSnapshot(value.snapshot, value.animate); });
onMounted(() => {
    map = createMtrMap(container.value!, {
        onSelect: value => emit('select', value),
        onError: message => emit('error', message),
    });
    map.setLanguage(props.language);
    map.setPowerSave(props.powerSave);
    if (props.frame) map.setSnapshot(props.frame.snapshot, props.frame.animate);
    followSelection();
});
onBeforeUnmount(() => {
    cancelAnimationFrame(anchorFrame);
    map?.destroy();
});
</script>

<template>
    <div ref="container" class="map" :aria-label="language === 'zh' ? '港鐵地圖' : 'MTR map'" />
</template>
