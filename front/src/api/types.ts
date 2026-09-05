// HTTP response contracts implemented by backend/.
export type TrainState = 'running' | 'dwell';

export interface TrainPosition {
    estimate?: 'planned' | 'observed' | 'predicted' | 'stale' | 'conflict';
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
    previousTime: string;
    nextTime: string | null;
}

export interface TrainSnapshot {
    timestamp: string;
    trains: TrainPosition[];
}

export interface RealtimeStatus {
    enabled: true;
    healthy: boolean | null;
    lastPollAt: string | null;
    lastError: string | null;
    updatedTrains: number;
}

export interface LineIncident {
    message: string;
    url: string | null;
    isDelay: boolean;
    updatedAt: string;
}

export interface OperationsSnapshot {
    realtime: RealtimeStatus | { enabled: false };
    lines: Array<{
        lineId: string;
        incident: LineIncident | null;
    }>;
}

export interface NetworkCatalog {
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
