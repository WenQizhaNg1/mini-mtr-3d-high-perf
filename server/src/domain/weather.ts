export interface WeatherReading {
    icon: number;
    temperatureC: number;
    humidityPercent: number;
    warnings: string[];
    updatedAt: string;
}

type JsonObject = Record<string, unknown>;

function object(value: unknown): JsonObject | null {
    return value && typeof value === 'object' && !Array.isArray(value)
        ? value as JsonObject
        : null;
}

function dataRows(value: unknown): JsonObject[] {
    const container = object(value);
    return Array.isArray(container?.data)
        ? container.data.map(object).filter((row): row is JsonObject => row !== null)
        : [];
}

function finiteNumber(value: unknown, label: string): number {
    if (
        (typeof value !== 'number' && typeof value !== 'string')
        || (typeof value === 'string' && value.trim() === '')
    ) {
        throw new Error(`HKO weather ${label} is missing or invalid`);
    }
    const result = Number(value);
    if (!Number.isFinite(result)) throw new Error(`HKO weather ${label} is missing or invalid`);
    return result;
}

export function normalizeWeather(value: unknown): WeatherReading {
    const weather = object(value);
    if (!weather) throw new Error('HKO weather response must be an object');

    const temperatures = dataRows(weather.temperature);
    const observatory = temperatures.find(row => (
        /Observatory|天文台/i.test(typeof row.place === 'string' ? row.place : '')
    )) ?? temperatures[0];
    const humidity = dataRows(weather.humidity)[0];
    const rawIcon = Array.isArray(weather.icon) ? weather.icon[0] : weather.icon;
    const updatedAt = typeof weather.updateTime === 'string' ? weather.updateTime : '';
    if (!updatedAt) throw new Error('HKO weather update time is missing');

    return {
        icon: finiteNumber(rawIcon, 'icon'),
        temperatureC: finiteNumber(observatory?.value, 'temperature'),
        humidityPercent: finiteNumber(humidity?.value, 'humidity'),
        warnings: Array.isArray(weather.warningMessage)
            ? weather.warningMessage.filter((item): item is string => typeof item === 'string')
            : [],
        updatedAt,
    };
}
