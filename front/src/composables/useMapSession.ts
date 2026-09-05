import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue';
import { usePreferencesStore } from '../stores/preferences';
import { useTransitStore } from '../stores/transit';

export function useMapSession(operator: Ref<string>, refresh: Ref<number>) {
    const preferences = usePreferencesStore();
    const transit = useTransitStore();
    const lifetime = new AbortController();
    let weatherRequest: AbortController | undefined;
    let networkRequest: AbortController | undefined;
    let metadataTimer: ReturnType<typeof setInterval> | undefined;
    let weatherTimer: ReturnType<typeof setInterval> | undefined;

    function loadWeather() {
        weatherRequest?.abort();
        if (operator.value !== 'mtr') { transit.weather = undefined; return; }
        weatherRequest = new AbortController();
        void transit.loadWeather(preferences.language, weatherRequest.signal);
    }
    function loadNetwork() {
        networkRequest?.abort();
        transit.network = undefined;
        transit.errors.network = '';
        if (!operator.value) return;
        networkRequest = new AbortController();
        void transit.loadNetwork(networkRequest.signal, operator.value);
    }
    watch([operator, refresh], loadNetwork);
    watch(operator, loadWeather);
    watch(() => preferences.language, () => {
        transit.weather = undefined;
        transit.errors.weather = '';
        loadWeather();
    });
    onMounted(() => {
        loadNetwork();
        loadWeather();
        metadataTimer = setInterval(() => {
            if (!transit.network) loadNetwork();
        }, 30_000);
        weatherTimer = setInterval(loadWeather, 600_000);
    });
    onBeforeUnmount(() => {
        lifetime.abort();
        weatherRequest?.abort();
        networkRequest?.abort();
        clearInterval(metadataTimer);
        clearInterval(weatherTimer);
        transit.loading = { network: false, weather: false };
    });
}
