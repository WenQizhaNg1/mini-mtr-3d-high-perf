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
import type { TrainSnapshot } from '../api/types';
import type { MapSelection } from '../map/createMap';

const { language, powerSave } = storeToRefs(usePreferencesStore());
const transit = useTransitStore();
const { network, operations, weather } = storeToRefs(transit);
const { text } = useDisplay(() => language.value);
const selection = ref<MapSelection>(null);
const selectionPoint = ref<{ x: number; y: number } | null>(null);
const about = ref(false);
const mapError = ref('');
const errors = computed(() => ({ ...transit.errors, map: mapError.value }));
const frame = shallowRef<{ snapshot: TrainSnapshot; animate: boolean }>();
const { live, playing, speed, connected, loading, error, at, snapshot, service,
    rangeStart, rangeEnd, seek, goLive, toggle } = usePlayback((snapshot, animate) => {
        frame.value = { snapshot, animate };
    });
useMapSession();
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
onMounted(() => {
    goLive();
    window.addEventListener('keydown', escape);
});
onBeforeUnmount(() => window.removeEventListener('keydown', escape));
</script>

<template>
    <main class="map-shell">
        <MapCanvas :language="language" :power-save="powerSave" :selection="selection" :frame="frame"
            @select="selection = $event" @point="selectionPoint = $event" @error="mapError = $event" />
        <StatusPanel :language="language" :live="live" :connected="connected" :at="at"
            :network="network" :operations="operations" :weather="weather" :snapshot="snapshot"
            :service="service" :errors="errors" :error="error" />
        <SelectionPopup :language="language" :network="network" :selected-train="selectedTrain"
            :selected-station="selectedStation" :point="selectionPoint" @close="selection = null" />
        <TimelinePanel :live="live" :playing="playing" :speed="speed" :power-save="powerSave" :language="language" :at="at"
            :start="service ? Date.parse(service.startsAt) : rangeStart" :end="service ? Date.parse(service.endsAt) : rangeEnd" :ready="!!service" :replay-available="!!service?.replay.startsAt" :loading="loading"
            @toggle="toggle" @go-live="goLive" @speed="speed = $event" @power-save="powerSave = !powerSave"
            @language="language = language === 'zh' ? 'en' : 'zh'" @seek="seek" />
        <button class="ui-button about-toggle" :class="{ active: about }" @click="about = true">{{ text('關於', 'About') }}</button>
        <AboutDialog v-if="about" :language="language" @close="about = false" />
    </main>
</template>
