import { ref, shallowRef } from 'vue';
import { defineStore } from 'pinia';
import { getNetwork, getOperations, getWeather } from '../api/transit';
import type { NetworkCatalog, OperationsSnapshot, WeatherSnapshot } from '../api/types';

export const useTransitStore = defineStore('transit', () => {
    const network = shallowRef<NetworkCatalog>();
    const operations = shallowRef<OperationsSnapshot>();
    const weather = shallowRef<WeatherSnapshot>();
    const errors = ref({ network: '', operations: '', weather: '' });
    const loading = ref({ network: false, operations: false, weather: false });

    async function loadNetwork(signal: AbortSignal) {
        loading.value.network = true;
        try {
            const value = await getNetwork(signal);
            if (!signal.aborted) { network.value = value; errors.value.network = ''; }
        } catch (cause) { if (!signal.aborted) errors.value.network = String(cause); }
        finally { if (!signal.aborted) loading.value.network = false; }
    }
    async function loadOperations(signal: AbortSignal) {
        loading.value.operations = true;
        try {
            const value = await getOperations(signal);
            if (!signal.aborted) { operations.value = value; errors.value.operations = ''; }
        } catch (cause) { if (!signal.aborted) errors.value.operations = String(cause); }
        finally { if (!signal.aborted) loading.value.operations = false; }
    }
    async function loadWeather(language: 'en' | 'zh', signal: AbortSignal) {
        loading.value.weather = true;
        try {
            const value = await getWeather(language, signal);
            if (!signal.aborted) { weather.value = value; errors.value.weather = ''; }
        } catch (cause) { if (!signal.aborted) errors.value.weather = String(cause); }
        finally { if (!signal.aborted) loading.value.weather = false; }
    }
    return { network, operations, weather, errors, loading, loadNetwork, loadOperations, loadWeather };
});
