export type TrainState = 'running' | 'dwell';

export interface TrainPositionRow extends Record<string, unknown> {
    id: string;
    line_id: string;
    pattern_id: string;
    colour: string;
    lng: number;
    lat: number;
    bearing: number;
    state: TrainState;
    previous_station: string;
    next_station: string | null;
    destination_station: string;
    delay_seconds: number;
    previous_time: Date | string;
    next_time: Date | string | null;
}

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

export interface RoutePatternChoice {
    default: string;
    alternate?: string;
    every?: number;
}

export interface ServiceLineConfig {
    lineId: string;
    apiCode: string;
    monitorStation: string;
    nameEn: string;
    nameZh: string;
    colour: string;
    incidentAnchorStation: string;
    speedKmph: number;
    firstTrainOffsetMinutes: number;
    lastTrainOffsetMinutes: number;
    headways: {
        peak: number;
        normal: number;
        evening: number;
        late: number;
    };
    patterns: {
        UP: RoutePatternChoice;
        DOWN: RoutePatternChoice;
    };
}

export interface ServiceConfig {
    schemaVersion: number;
    serviceDayStart: string;
    serviceEndOffsetMinutes: number;
    dwellSeconds: number;
    travelTimeFactor: number;
    lines: ServiceLineConfig[];
}

export interface MtrTrainEta {
    dest?: string;
    time?: string;
    valid?: string;
}

export interface MtrScheduleResponse {
    sys_time?: string;
    curr_time?: string;
    status?: number;
    message?: string;
    url?: string;
    isdelay?: string;
    data?: Record<string, {
        sys_time?: string;
        curr_time?: string;
        UP?: MtrTrainEta[];
        DOWN?: MtrTrainEta[];
    }>;
}

export interface Monitor {
    lineId: string;
    apiCode: string;
    stationCode: string;
    patterns: ServiceLineConfig['patterns'];
}

export interface Arrival {
    at: Date;
    destination: string;
}

export interface TrainCandidate {
    id: string;
    destination: string;
    scheduledAt: Date;
}

export interface RealtimeMatch {
    trainId: string;
    delaySeconds: number;
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

export type WeatherLanguage = 'en' | 'zh';

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
