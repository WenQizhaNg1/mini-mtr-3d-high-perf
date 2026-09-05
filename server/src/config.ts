import { readFileSync } from 'node:fs';
import type { RoutePatternChoice, ServiceConfig, ServiceLineConfig } from './domain/types.js';

export interface RuntimeConfig {
    connectionString: string;
    host: string;
    port: number;
    realtimeEnabled: boolean;
    service: ServiceConfig;
}

function isPositiveNumber(value: unknown): value is number {
    return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function isNonNegativeNumber(value: unknown): value is number {
    return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function isServiceDayStart(value: unknown): value is string {
    if (typeof value !== 'string') return false;
    const match = /^(\d{2}):(\d{2}):(\d{2})\+08:00$/.exec(value);
    if (!match) return false;
    return Number(match[1]) < 24
        && Number(match[2]) < 60
        && Number(match[3]) < 60;
}

function isPatternChoice(value: unknown): value is RoutePatternChoice {
    if (!value || typeof value !== 'object') return false;
    const choice = value as Partial<RoutePatternChoice>;
    return typeof choice.default === 'string'
        && (choice.alternate === undefined || typeof choice.alternate === 'string')
        && (choice.every === undefined || (Number.isInteger(choice.every) && choice.every > 0))
        && ((choice.alternate === undefined) === (choice.every === undefined));
}

function isServiceLine(value: unknown): value is ServiceLineConfig {
    if (!value || typeof value !== 'object') return false;
    const line = value as Partial<ServiceLineConfig>;
    return typeof line.lineId === 'string'
        && typeof line.apiCode === 'string'
        && typeof line.monitorStation === 'string'
        && typeof line.nameEn === 'string'
        && typeof line.nameZh === 'string'
        && /^#[0-9a-f]{6}$/i.test(line.colour || '')
        && typeof line.incidentAnchorStation === 'string'
        && isPositiveNumber(line.speedKmph)
        && isNonNegativeNumber(line.firstTrainOffsetMinutes)
        && isNonNegativeNumber(line.lastTrainOffsetMinutes)
        && line.lastTrainOffsetMinutes >= line.firstTrainOffsetMinutes
        && isPositiveNumber(line.headways?.peak)
        && isPositiveNumber(line.headways?.normal)
        && isPositiveNumber(line.headways?.evening)
        && isPositiveNumber(line.headways?.late)
        && isPatternChoice(line.patterns?.UP)
        && isPatternChoice(line.patterns?.DOWN);
}

export function readServiceConfig(): ServiceConfig {
    const path = new URL('../../data/mtr-service.json', import.meta.url);
    const value: unknown = JSON.parse(readFileSync(path, 'utf8'));
    if (!value || typeof value !== 'object') {
        throw new Error('data/mtr-service.json must be an object');
    }
    const service = value as Partial<ServiceConfig>;
    if (
        service.schemaVersion !== 1
        || !isServiceDayStart(service.serviceDayStart)
        || !isPositiveNumber(service.serviceEndOffsetMinutes)
        || !isNonNegativeNumber(service.dwellSeconds)
        || !isPositiveNumber(service.travelTimeFactor)
        || !Array.isArray(service.lines)
        || !service.lines.every(isServiceLine)
    ) {
        throw new Error('data/mtr-service.json has an unsupported schema');
    }
    return service as ServiceConfig;
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): RuntimeConfig {
    const connectionString = environment.DATABASE_URL;
    if (!connectionString) throw new Error('DATABASE_URL is required');

    const port = Number(environment.API_PORT || 3001);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`Invalid API_PORT: ${environment.API_PORT}`);
    }

    return {
        connectionString,
        host: environment.API_HOST || '127.0.0.1',
        port,
        realtimeEnabled: environment.REALTIME_ENABLED !== 'false',
        service: readServiceConfig(),
    };
}
