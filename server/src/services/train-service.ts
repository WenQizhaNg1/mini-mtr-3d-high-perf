import type { Database } from '../db/client.js';
import { loadTrainPositions } from '../db/train-queries.js';
import type { TrainPosition, TrainPositionRow, TrainSnapshot } from '../domain/types.js';

function isoTimestamp(value: Date | string): string {
    return (value instanceof Date ? value : new Date(value)).toISOString();
}

function mapTrain(row: TrainPositionRow): TrainPosition {
    return {
        id: row.id,
        lineId: row.line_id,
        patternId: row.pattern_id,
        colour: row.colour,
        lng: row.lng,
        lat: row.lat,
        bearing: row.bearing,
        state: row.state,
        previousStation: row.previous_station,
        nextStation: row.next_station,
        destinationStation: row.destination_station,
        delaySeconds: row.delay_seconds,
        previousTime: isoTimestamp(row.previous_time),
        nextTime: row.next_time ? isoTimestamp(row.next_time) : null,
    };
}

export interface TrainService {
    loadSnapshot(at: Date): Promise<TrainSnapshot>;
}

export function createTrainService(db: Database): TrainService {
    return {
        async loadSnapshot(at) {
            const rows = await loadTrainPositions(db, at);
            return {
                timestamp: at.toISOString(),
                trains: rows.map(mapTrain),
            };
        },
    };
}
