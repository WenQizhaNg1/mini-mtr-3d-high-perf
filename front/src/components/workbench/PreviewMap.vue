<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { Map, NavigationControl, setWorkerUrl, type StyleSpecification } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import type { FeatureCollection, Geometry } from 'geojson';

const props = defineProps<{ style?: StyleSpecification; sample?: FeatureCollection; bounds?: number[] }>();
const emit = defineEmits<{ error: [message: string] }>();
const container = ref<HTMLElement>();
let map: Map | undefined;
defineExpose({ getMap: () => map });
let timer: ReturnType<typeof setTimeout>;
setWorkerUrl(workerUrl);

function draw() {
    if (!map || !props.style) return;
    const style: StyleSpecification = JSON.parse(JSON.stringify(props.style));
    if (style.sources['mtr-trains']?.type === 'geojson') style.sources['mtr-trains'].data = {
        type: 'FeatureCollection', features: [{ type: 'Feature', properties: { id: 'sample', colour: '#e84057', height: 18 },
            geometry: { type: 'Polygon', coordinates: [[[114.163, 22.312], [114.164, 22.312], [114.164, 22.3122], [114.163, 22.3122], [114.163, 22.312]]] } }],
    };
    if (props.sample) {
        style.sources['workbench-sample'] = { type: 'geojson', data: props.sample };
        style.layers.push(
            { id: 'workbench-sample-fill', type: 'fill', source: 'workbench-sample', filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': '#f97316', 'fill-opacity': 0.4 } },
            { id: 'workbench-sample-line', type: 'line', source: 'workbench-sample', filter: ['!=', ['geometry-type'], 'Point'], paint: { 'line-color': '#f97316', 'line-width': 4 } },
            { id: 'workbench-sample-point', type: 'circle', source: 'workbench-sample', filter: ['==', ['geometry-type'], 'Point'], paint: { 'circle-color': '#f97316', 'circle-radius': 9, 'circle-stroke-width': 2, 'circle-stroke-color': '#ffffff' } },
        );
    }
    map.setStyle(style);
}
function schedule() { clearTimeout(timer); timer = setTimeout(draw, 180); }
watch(() => props.style, schedule, { deep: true });
function fitSample(value?: FeatureCollection) {
    if (!value) return;
    const coordinates: number[][] = [];
    function walk(value: unknown) {
        if (!Array.isArray(value)) return;
        if (typeof value[0] === 'number') coordinates.push(value as number[]); else value.forEach(walk);
    }
    for (const feature of value.features) {
        const geometry = feature.geometry as Exclude<Geometry, { type: 'GeometryCollection' }>;
        if (geometry) walk(geometry.coordinates);
    }
    if (coordinates.length) {
        const extent = [Infinity, Infinity, -Infinity, -Infinity];
        for (const coordinate of coordinates) {
            extent[0] = Math.min(extent[0], coordinate[0]); extent[1] = Math.min(extent[1], coordinate[1]);
            extent[2] = Math.max(extent[2], coordinate[0]); extent[3] = Math.max(extent[3], coordinate[1]);
        }
        fit(extent);
    }
}
watch(() => props.sample, value => { schedule(); fitSample(value); });
function fit(bounds?: number[]) { if (bounds?.length === 4) map?.fitBounds([[bounds[0], bounds[1]], [bounds[2], bounds[3]]], { padding: 70, maxZoom: 16, duration: 500 }); }
watch(() => props.bounds, fit);
onMounted(() => {
    map = new Map({ container: container.value!, style: { version: 8, sources: {}, layers: [] }, center: [114.17, 22.315], zoom: 12, pitch: 35 });
    map.addControl(new NavigationControl());
    map.on('error', e => emit('error', e.error.message));
    draw();
    fitSample(props.sample);
});
onBeforeUnmount(() => { clearTimeout(timer); map?.remove(); });
</script>

<template><div ref="container" class="wb-preview" aria-label="样式预览地图" /></template>
