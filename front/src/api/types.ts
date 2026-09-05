// HTTP response contracts implemented by backend/.
export type TrainState = 'running' | 'dwell' | 'unknown';
export type MotionMode = 'simulation' | 'realtime';
export interface Operator { code: string; name: string; timezone: string; mode: MotionMode; modes: MotionMode[] }
export interface Arrival {
    id: string; vehicleId?: string; lineId: string; stationId: string; destinationId?: string;
    observedAt: string; eta: string; stale: boolean;
}

export interface TrainPosition {
    estimate?: 'simulated' | 'observed' | 'stale';
    observedAt?: string;
    validUntil?: string;
    motion?: Array<{ at: number; lng: number; lat: number; bearing: number }>;
    id: string;
    lineId: string;
    patternId: string;
    colour: string;
    lng: number;
    lat: number;
    bearing: number;
    state: TrainState;
    previousStation: string;
    nextStation: string | null;
    destinationStation: string;
    delaySeconds: number;
    previousTime: string | null;
    nextTime: string | null;
}

export interface TrainSnapshot {
    operator?: string;
    mode?: MotionMode;
    arrivals?: Arrival[];
    status?: 'ok' | 'empty' | 'unavailable' | 'stale';
    error?: string;
    timestamp: string;
    trains: TrainPosition[];
}

export interface NetworkCatalog {
    operator: string;
    name: string;
    timezone: string;
    mode: MotionMode;
    modes: MotionMode[];
    bounds: number[];
    service: {
        serviceDayStart: string;
        serviceEndOffsetMinutes: number;
    };
    lines: Array<{
        id: string;
        apiCode: string;
        nameEn: string;
        nameZh: string;
        colour: string;
        incidentAnchorStation: string;
    }>;
    stations: Array<{
        code: string;
        nameEn: string;
        nameZh: string;
        interchange: boolean;
        lineIds: string[];
    }>;
}

export interface ServiceDaySnapshot {
    serviceDate: string;
    active: boolean;
    startsAt: string;
    endsAt: string;
    replay: {
        startsAt: string | null;
        endsAt: string | null;
    };
    schedules: {
        current: boolean;
        nextServiceDate: string;
        next: boolean;
    };
}

export interface WeatherSnapshot {
    icon: number;
    temperatureC: number;
    humidityPercent: number;
    warnings: string[];
    updatedAt: string;
    cachedAt: string;
    stale: boolean;
    error: string | null;
}
