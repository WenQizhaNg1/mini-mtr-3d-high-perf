import { onBeforeUnmount, onMounted, watch } from 'vue';
import { usePreferencesStore } from '../stores/preferences';
import { useTransitStore } from '../stores/transit';

export function useMapSession() {
    const preferences = usePreferencesStore();
    const transit = useTransitStore();
    const lifetime = new AbortController();
    let weatherRequest: AbortController | undefined;
    let metadataTimer: ReturnType<typeof setInterval> | undefined;
    let weatherTimer: ReturnType<typeof setInterval> | undefined;

    function loadWeather() {
        weatherRequest?.abort();
        weatherRequest = new AbortController();
        void transit.loadWeather(preferences.language, weatherRequest.signal);
    }
    watch(() => preferences.language, () => {
        transit.weather = undefined;
        transit.errors.weather = '';
        loadWeather();
    });
    onMounted(() => {
        void transit.loadNetwork(lifetime.signal);
        void transit.loadOperations(lifetime.signal);
        loadWeather();
        metadataTimer = setInterval(() => {
            void transit.loadOperations(lifetime.signal);
            if (!transit.network) void transit.loadNetwork(lifetime.signal);
        }, 30_000);
        weatherTimer = setInterval(loadWeather, 600_000);
    });
    onBeforeUnmount(() => {
        lifetime.abort();
        weatherRequest?.abort();
        clearInterval(metadataTimer);
        clearInterval(weatherTimer);
        transit.loading = { network: false, operations: false, weather: false };
    });
}
