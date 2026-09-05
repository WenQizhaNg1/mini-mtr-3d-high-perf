import { ref, shallowRef } from 'vue';
import { defineStore } from 'pinia';
import { getNetwork, getWeather } from '../api/transit';
import type { NetworkCatalog, WeatherSnapshot } from '../api/types';

export const useTransitStore = defineStore('transit', () => {
    const network = shallowRef<NetworkCatalog>();
    const weather = shallowRef<WeatherSnapshot>();
    const errors = ref({ network: '', weather: '' });
    const loading = ref({ network: false, weather: false });

    async function loadNetwork(signal: AbortSignal, operator = 'mtr') {
        loading.value.network = true;
        try {
            const value = await getNetwork(signal, operator);
            if (!signal.aborted) { network.value = value; errors.value.network = ''; }
        } catch (cause) { if (!signal.aborted) errors.value.network = String(cause); }
        finally { if (!signal.aborted) loading.value.network = false; }
    }
    async function loadWeather(language: 'en' | 'zh', signal: AbortSignal) {
        loading.value.weather = true;
        try {
            const value = await getWeather(language, signal);
            if (!signal.aborted) { weather.value = value; errors.value.weather = ''; }
        } catch (cause) { if (!signal.aborted) errors.value.weather = String(cause); }
        finally { if (!signal.aborted) loading.value.weather = false; }
    }
    return { network, weather, errors, loading, loadNetwork, loadWeather };
});
