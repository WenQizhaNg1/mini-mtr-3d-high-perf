import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeWeather } from '../src/domain/weather.js';
import type { WeatherClient } from '../src/integrations/weather-client.js';
import { createWeatherService } from '../src/services/weather-service.js';

const response = {
    icon: [51],
    temperature: {
        data: [
            { place: 'Sha Tin', value: 29 },
            { place: 'Hong Kong Observatory', value: 30 },
        ],
    },
    humidity: { data: [{ value: 78 }] },
    warningMessage: ['Very Hot Weather Warning'],
    updateTime: '2026-09-05T08:00:00+08:00',
};

test('normalizes the HKO reading without presentation-specific icon mapping', () => {
    assert.deepEqual(normalizeWeather(response), {
        icon: 51,
        temperatureC: 30,
        humidityPercent: 78,
        warnings: ['Very Hot Weather Warning'],
        updatedAt: '2026-09-05T08:00:00+08:00',
    });
    assert.throws(
        () => normalizeWeather({ ...response, icon: null }),
        /icon is missing or invalid/,
    );
});

test('caches weather by language and preserves the last success after a refresh failure', async () => {
    let currentTime = new Date('2026-09-05T00:00:00Z');
    let requests = 0;
    let fail = false;
    const languages: string[] = [];
    const client: WeatherClient = {
        async getCurrent(language) {
            requests++;
            languages.push(language);
            if (fail) throw new Error('weather unavailable');
            return response;
        },
    };
    const weather = createWeatherService(client, () => currentTime);

    const first = await weather.load('en');
    const cached = await weather.load('en');
    assert.equal(requests, 1);
    assert.deepEqual(cached, first);

    await weather.load('zh');
    assert.deepEqual(languages, ['en', 'tc']);

    currentTime = new Date(currentTime.getTime() + 10 * 60_000 + 1);
    fail = true;
    const stale = await weather.load('en');
    assert.equal(stale.temperatureC, 30);
    assert.equal(stale.stale, true);
    assert.equal(stale.error, 'weather unavailable');
    await weather.load('en');
    assert.equal(requests, 3);
});
