import { normalizeWeather } from '../domain/weather.js';
import type { WeatherLanguage, WeatherSnapshot } from '../domain/types.js';
import type { WeatherClient } from '../integrations/weather-client.js';

const CACHE_MILLISECONDS = 10 * 60_000;
const FAILURE_RETRY_MILLISECONDS = 60_000;

export interface WeatherService {
    load(language: WeatherLanguage): Promise<WeatherSnapshot>;
}

interface CacheEntry {
    value: WeatherSnapshot;
    expiresAt: number;
}

export function createWeatherService(
    client: WeatherClient,
    now: () => Date = () => new Date(),
): WeatherService {
    const cache = new Map<WeatherLanguage, CacheEntry>();
    const pending = new Map<WeatherLanguage, Promise<WeatherSnapshot>>();

    const refresh = async (language: WeatherLanguage): Promise<WeatherSnapshot> => {
        const startedAt = now();
        try {
            const reading = normalizeWeather(await client.getCurrent(language === 'zh' ? 'tc' : 'en'));
            const value: WeatherSnapshot = {
                ...reading,
                cachedAt: startedAt.toISOString(),
                stale: false,
                error: null,
            };
            cache.set(language, {
                value,
                expiresAt: startedAt.getTime() + CACHE_MILLISECONDS,
            });
            return value;
        } catch (error) {
            const previous = cache.get(language)?.value;
            if (!previous) throw error;
            const value = {
                ...previous,
                stale: true,
                error: error instanceof Error ? error.message : String(error),
            };
            cache.set(language, {
                value,
                expiresAt: startedAt.getTime() + FAILURE_RETRY_MILLISECONDS,
            });
            return value;
        }
    };

    return {
        load(language) {
            const cached = cache.get(language);
            if (cached && cached.expiresAt > now().getTime()) {
                return Promise.resolve(cached.value);
            }
            const inFlight = pending.get(language);
            if (inFlight) return inFlight;
            const request = refresh(language).finally(() => pending.delete(language));
            pending.set(language, request);
            return request;
        },
    };
}
