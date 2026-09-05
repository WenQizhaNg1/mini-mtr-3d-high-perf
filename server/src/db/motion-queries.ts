import type { Pool } from 'pg';
import { distance, type Coordinate, type MotionCheckpoint, type PlannedRun, type RoutePath } from '../domain/motion.js';

export async function loadMotionPaths(pool: Pool): Promise<Map<string, RoutePath>> {
    const { rows } = await pool.query('SELECT id, line_id, colour, to_code, ST_AsGeoJSON(geom)::json AS geometry FROM mtr.route_patterns');
    return new Map(rows.map(row => {
        const coordinates = row.geometry.coordinates as Coordinate[];
        const distances = [0];
        for (let i = 1; i < coordinates.length; i++) distances.push(distances[i - 1]! + distance(coordinates[i - 1]!, coordinates[i]!));
        return [row.id, { id: row.id, lineId: row.line_id, colour: row.colour, destination: row.to_code, coordinates, distances }];
    }));
}

// PostGIS fractions are measured in the geometry's coordinate system (4326).
// Convert them once to the metric progress used by the motion engine.
function metricDistance(path: RoutePath, fraction: number): number {
    const units = [0];
    for (let i = 1; i < path.coordinates.length; i++) {
        const a = path.coordinates[i - 1]!, b = path.coordinates[i]!;
        units.push(units[i - 1]! + Math.hypot(b[0] - a[0], b[1] - a[1]));
    }
    const target = units.at(-1)! * fraction;
    let i = 1;
    while (i < units.length - 1 && units[i]! < target) i++;
    return path.distances[i - 1]! + (target - units[i - 1]!) / (units[i]! - units[i - 1]!) * (path.distances[i]! - path.distances[i - 1]!);
}

export async function loadMotionRuns(pool: Pool, paths: Map<string, RoutePath>, now: number): Promise<PlannedRun[]> {
    const { rows } = await pool.query(`
        SELECT run.id, run.pattern_id, leg.departure_at, leg.arrival_at,
               a.station_code AS from_station, a.fraction AS from_fraction,
               b.station_code AS to_station, b.fraction AS to_fraction
        FROM mtr.train_runs run JOIN mtr.train_legs leg ON leg.train_id = run.id
        JOIN mtr.route_stops a ON a.pattern_id = run.pattern_id AND a.stop_sequence = leg.from_stop_sequence
        JOIN mtr.route_stops b ON b.pattern_id = run.pattern_id AND b.stop_sequence = leg.to_stop_sequence
        WHERE run.starts_at <= $1 AND run.ends_at >= $2 ORDER BY run.id, leg.sequence
    `, [new Date(now + 30 * 60_000), new Date(now - 2 * 3600_000)]);
    const runs = new Map<string, PlannedRun>();
    const distances = new Map<string, number>();
    for (const row of rows) {
        let run = runs.get(row.id);
        if (!run) { run = { id: row.id, patternId: row.pattern_id, nodes: [] }; runs.set(row.id, run); }
        const path = paths.get(row.pattern_id)!;
        for (const [fraction, station, at] of [
            [row.from_fraction, row.from_station, row.departure_at],
            [row.to_fraction, row.to_station, row.arrival_at],
        ] as Array<[number, string, Date]>) {
            const key = `${path.id}:${fraction}`;
            if (!distances.has(key)) distances.set(key, metricDistance(path, fraction));
            run.nodes.push({ at: at.getTime(), distance: distances.get(key)!, station });
        }
    }
    return [...runs.values()];
}

export async function loadMotionCheckpoint(pool: Pool): Promise<MotionCheckpoint | undefined> {
    const { rows } = await pool.query('SELECT state FROM mtr.motion_checkpoint WHERE id = 1');
    return rows[0]?.state;
}

export async function saveMotionCheckpoint(pool: Pool, state: MotionCheckpoint): Promise<void> {
    await pool.query(`INSERT INTO mtr.motion_checkpoint (id, state) VALUES (1, $1)
        ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state`, [JSON.stringify(state)]);
}
