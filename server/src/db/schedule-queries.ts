import type { Client, Pool } from 'pg';
import type { GeneratedSchedule, ScheduleStop } from '../domain/schedule.js';
import { insertRows } from './batch.js';
import { NETWORK_DATA_LOCK } from './locks.js';

type QueryClient = Pick<Client, 'query'>;

export async function ensureMissingSchedule(
    pool: Pool,
    serviceDate: string,
    build: (stops: ScheduleStop[]) => GeneratedSchedule,
): Promise<boolean> {
    const client = await pool.connect();
    try {
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [NETWORK_DATA_LOCK]);
        const existing = await client.query(
            'SELECT 1 FROM mtr.train_runs WHERE service_date = $1 LIMIT 1', [serviceDate],
        );
        if (existing.rows.length > 0) {
            await client.query('COMMIT');
            return false;
        }
        const schedule = build(await loadScheduleStops(client));
        if (schedule.runs.length === 0 || schedule.legs.length === 0) {
            throw new Error(`No schedule generated for ${serviceDate}; check imported route stops`);
        }
        await replaceSchedule(client, serviceDate, schedule);
        await client.query('COMMIT');
        return true;
    } catch (error) {
        try {
            await client.query('ROLLBACK');
        } catch (rollbackError) {
            throw new AggregateError([error, rollbackError], `Schedule preparation and rollback failed for ${serviceDate}`);
        }
        throw error;
    } finally {
        client.release();
    }
}

export async function loadScheduleStops(client: QueryClient): Promise<ScheduleStop[]> {
    const result = await client.query<{
        pattern_id: string;
        stop_sequence: number;
        station_code: string;
        distance_m: number;
    }>(`
        SELECT pattern_id, stop_sequence, station_code, distance_m
        FROM mtr.route_stops
        ORDER BY pattern_id, stop_sequence
    `);
    return result.rows.map(row => ({
        patternId: row.pattern_id,
        stopSequence: row.stop_sequence,
        stationCode: row.station_code,
        distanceM: row.distance_m,
    }));
}

export async function deleteSchedules(
    client: QueryClient,
    serviceDates: string[],
): Promise<void> {
    if (serviceDates.length === 0) return;
    const placeholders = serviceDates.map((_, index) => `$${index + 1}`).join(', ');
    await client.query(
        `DELETE FROM mtr.train_runs WHERE service_date IN (${placeholders})`,
        serviceDates,
    );
}

export async function replaceSchedule(
    client: QueryClient,
    serviceDate: string,
    schedule: GeneratedSchedule,
): Promise<void> {
    await deleteSchedules(client, [serviceDate]);
    await insertRows(client, `
        INSERT INTO mtr.train_runs (id, service_date, pattern_id, starts_at, ends_at)
    `, 5, schedule.runs.map(run => [
        run.id,
        run.serviceDate,
        run.patternId,
        run.startsAt,
        run.endsAt,
    ]));
    await insertRows(client, `
        INSERT INTO mtr.train_legs (
            train_id, sequence, from_stop_sequence, to_stop_sequence, departure_at, arrival_at
        )
    `, 6, schedule.legs.map(leg => [
        leg.trainId,
        leg.sequence,
        leg.fromStopSequence,
        leg.toStopSequence,
        leg.departureAt,
        leg.arrivalAt,
    ]));
}
