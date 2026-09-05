export interface LineStringGeometry {
    type: 'LineString';
    coordinates: [number, number][];
}

export interface PointGeometry {
    type: 'Point';
    coordinates: [number, number];
}

export interface RoutePatternInput {
    id: string;
    osmRelationId: number;
    lineId: string;
    fromCode: string;
    toCode: string;
    fromName: string;
    toName: string;
    via: string;
    colour: string;
    lengthM: number;
    stopCount: number;
    geom: LineStringGeometry;
    stops: Array<{
        stationCode: string;
        distanceM: number;
        pathCoordinate: [number, number];
    }>;
}

export interface StationInput {
    code: string;
    name: string;
    nameEn: string;
    nameZh: string;
    lineIds: string;
    interchange: boolean;
    geom: PointGeometry;
}

export interface NetworkData {
    patterns: RoutePatternInput[];
    stations: StationInput[];
}

export interface RouteStopValue {
    patternId: string;
    stopSequence: number;
    stationCode: string;
    distanceM: number;
    fraction: number;
}

export interface NetworkState {
    patternIds: string[];
    stops: RouteStopValue[];
}

const DISTANCE_TOLERANCE_METRES = 0.001;
const FRACTION_TOLERANCE = 1e-9;

function stopsByPattern(stops: RouteStopValue[]): Map<string, RouteStopValue[]> {
    const result = new Map<string, RouteStopValue[]>();
    for (const stop of stops) {
        const patternStops = result.get(stop.patternId) ?? [];
        patternStops.push(stop);
        result.set(stop.patternId, patternStops);
    }
    for (const patternStops of result.values()) {
        patternStops.sort((a, b) => a.stopSequence - b.stopSequence);
    }
    return result;
}

function stopsMatch(left: RouteStopValue[], right: RouteStopValue[]): boolean {
    if (left.length !== right.length) return false;
    return left.every((stop, index) => {
        const other = right[index];
        return other !== undefined
            && stop.stopSequence === other.stopSequence
            && stop.stationCode === other.stationCode
            && Math.abs(stop.distanceM - other.distanceM) <= DISTANCE_TOLERANCE_METRES
            && Math.abs(stop.fraction - other.fraction) <= FRACTION_TOLERANCE;
    });
}

export function findChangedPatternIds(
    current: NetworkState,
    incoming: NetworkState,
): string[] {
    const currentStops = stopsByPattern(current.stops);
    const incomingStops = stopsByPattern(incoming.stops);
    const patternIds = new Set([...current.patternIds, ...incoming.patternIds]);
    return [...patternIds]
        .filter(patternId => !stopsMatch(
            currentStops.get(patternId) ?? [],
            incomingStops.get(patternId) ?? [],
        ))
        .sort();
}
