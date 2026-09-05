const HKO_WEATHER_URL = 'https://data.weather.gov.hk/weatherAPI/opendata/weather.php';

export interface WeatherClient {
    getCurrent(language: 'en' | 'tc', signal?: AbortSignal): Promise<unknown>;
}

export function createWeatherClient(): WeatherClient {
    return {
        async getCurrent(language, signal) {
            const url = new URL(HKO_WEATHER_URL);
            url.search = new URLSearchParams({
                dataType: 'rhrread',
                lang: language,
            }).toString();
            const response = await fetch(url, { signal });
            if (!response.ok) throw new Error(`HKO weather returned HTTP ${response.status}`);
            return response.json() as Promise<unknown>;
        },
    };
}
