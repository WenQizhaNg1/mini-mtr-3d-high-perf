import { sql } from 'drizzle-orm';
import type { Monitor, RealtimeMatch, TrainCandidate } from '../domain/types.js';
import type { DatabaseExecutor } from './client.js';

interface CandidateRow extends Record<string, unknown> {
    id: string;
    destination: string;
    scheduled_at: Date | string;
}

function patternIds(monitor: Monitor, direction: 'UP' | 'DOWN'): string[] {
    const patterns = monitor.patterns[direction];
    return [patterns.default, patterns.alternate].filter((id): id is string => Boolean(id));
}

export async function loadCandidates(
    db: DatabaseExecutor,
    monitor: Monitor,
    direction: 'UP' | 'DOWN',
    from: Date,
    to: Date,
): Promise<TrainCandidate[]> {
    const patterns = patternIds(monitor, direction);
    const result = await db.execute<CandidateRow>(sql`
        SELECT
            run.id,
            route.to_code AS destination,
            COALESCE(arriving.arrival_at, departing.departure_at) AS scheduled_at
        FROM mtr.route_stops AS stop
        JOIN mtr.train_runs AS run ON run.pattern_id = stop.pattern_id
        JOIN mtr.route_patterns AS route ON route.id = run.pattern_id
        LEFT JOIN mtr.train_legs AS arriving
          ON arriving.train_id = run.id
         AND arriving.to_stop_sequence = stop.stop_sequence
        LEFT JOIN mtr.train_legs AS departing
          ON departing.train_id = run.id
         AND departing.from_stop_sequence = stop.stop_sequence
        WHERE stop.pattern_id IN (${sql.join(patterns.map(id => sql`${id}`), sql`, `)})
          AND stop.station_code = ${monitor.stationCode}
          AND COALESCE(arriving.arrival_at, departing.departure_at) BETWEEN ${from} AND ${to}
        ORDER BY scheduled_at
    `);
    return result.rows.map(row => ({
        id: row.id,
        destination: row.destination,
        scheduledAt: new Date(row.scheduled_at),
    }));
}

export async function updateOffsets(
    db: DatabaseExecutor,
    matches: RealtimeMatch[],
    observedAt: Date,
): Promise<void> {
    if (matches.length === 0) return;
    const values = sql.join(matches.map(match => sql`(
        ${match.trainId}, ${match.delaySeconds}, ${observedAt}
    )`), sql`, `);
    await db.execute(sql`
        INSERT INTO mtr.realtime_offsets (train_id, delay_seconds, observed_at)
        VALUES ${values}
        ON CONFLICT (train_id) DO UPDATE SET
            delay_seconds = EXCLUDED.delay_seconds,
            observed_at = EXCLUDED.observed_at
    `);
}
