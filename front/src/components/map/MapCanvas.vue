<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createMtrMap, type MtrMap, type MapSelection } from '../../map/createMap';
import type { TrainSnapshot } from '../../api/types';
import { getFullStyle } from '../../api/styles';

const props = defineProps<{
    at: number;
    operator: string; timezone: string; refresh: number; bounds?: number[];
    language: 'en' | 'zh'; powerSave: boolean; selection: MapSelection;
    frame?: { snapshot: TrainSnapshot; animate: boolean };
}>();
const emit = defineEmits<{
    select: [value: MapSelection]; error: [message: string]; point: [value: { x: number; y: number } | null];
}>();
const container = ref<HTMLElement>();
const pitch = ref(0);
const dark = computed(() => {
    const hour = Number(new Intl.DateTimeFormat('en-GB', {timeZone: props.timezone, hour: '2-digit', hourCycle: 'h23'}).format(props.at));
    return hour < 6 || hour >= 18;
});
const appliedDark = ref(dark.value);
const skyColor = computed(() => appliedDark.value ? '#111b2b' : '#b9d2dd');
const horizonColor = computed(() => appliedDark.value ? '#222f40' : '#edf2f3');
// Screen-space haze is a visual approximation; fade it out in overhead views.
const hazeOpacity = computed(() => Math.max(0, Math.min(1, (pitch.value - 40) / 30)));
let map: MtrMap | undefined;
let anchorFrame = 0;
let styleRequest: AbortController | undefined;

function followSelection() {
    cancelAnimationFrame(anchorFrame);
    map?.selectTrain(props.selection?.kind === 'train' ? props.selection.id : null);
    const follow = () => {
        map?.followSelectedTrain();
        emit('point', props.selection ? map?.getSelectionPoint() || null : null);
        if (props.selection) anchorFrame = requestAnimationFrame(follow);
    };
    follow();
}
watch(() => props.selection, followSelection);
watch(() => props.language, value => map?.setLanguage(value));
watch(() => props.powerSave, value => map?.setPowerSave(value));
watch(() => props.frame, value => { if (value) map?.setSnapshot(value.snapshot, value.animate); });
async function loadStyle(night: boolean) {
    styleRequest?.abort();
    const request = new AbortController();
    styleRequest = request;
    try {
        const style = await getFullStyle(night ? 'mtr-dark' : 'mtr-light', request.signal, props.operator || 'mtr');
        if (request.signal.aborted) return;
        style.sky ??= {
            'sky-color': night ? '#111b2b' : '#b9d2dd',
            'horizon-color': night ? '#222f40' : '#edf2f3',
            'sky-horizon-blend': 0.8,
        };
        if (map) map.setStyle(style);
        else {
            map = createMtrMap(container.value!, {
                onSelect: value => emit('select', value),
                onError: message => emit('error', message),
                onPitch: value => pitch.value = value,
            }, style);
            map.setLanguage(props.language);
            map.setPowerSave(props.powerSave);
            if (props.operator !== 'mtr' && props.bounds?.length === 4) map.fitBounds(props.bounds);
            if (props.frame) map.setSnapshot(props.frame.snapshot, props.frame.animate);
            followSelection();
        }
        appliedDark.value = night;
        emit('error', '');
    } catch (cause) {
        if (!request.signal.aborted) emit('error', `Map style: ${String(cause)}`);
    }
}
onMounted(() => { void loadStyle(dark.value); });
watch(dark, night => { void loadStyle(night); });
watch(() => [props.operator, props.refresh], () => { void loadStyle(dark.value); });
watch(() => props.bounds, bounds => { if (props.operator !== 'mtr' && bounds?.length === 4) map?.fitBounds(bounds); });
onBeforeUnmount(() => {
    styleRequest?.abort();
    cancelAnimationFrame(anchorFrame);
    map?.destroy();
});
</script>

<template>
    <div ref="container" class="map" :aria-label="language === 'zh' ? '港鐵地圖' : 'MTR map'" />
    <div class="map-haze" aria-hidden="true" :style="{ opacity: hazeOpacity, '--haze-color': horizonColor, '--sky-color': skyColor }" />
</template>

<style scoped>
.map-haze {
    position: absolute;
    inset: 0;
    z-index: 1;
    pointer-events: none;
    background: linear-gradient(to bottom,
        var(--sky-color) 0%,
        color-mix(in srgb, var(--haze-color) 90%, transparent) 10%,
        color-mix(in srgb, var(--haze-color) 45%, transparent) 25%,
        color-mix(in srgb, var(--haze-color) 12%, transparent) 40%,
        transparent 55%);
}
</style>
