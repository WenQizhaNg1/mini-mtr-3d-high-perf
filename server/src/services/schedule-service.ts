import type { Client, Pool } from 'pg';
import { ensureMissingSchedule, loadScheduleStops, replaceSchedule } from '../db/schedule-queries.js';
import { generateSchedule, type GeneratedSchedule } from '../domain/schedule.js';
import type { ServiceConfig } from '../domain/types.js';

type QueryClient = Pick<Client, 'query'>;

export function ensureSchedule(pool: Pool, config: ServiceConfig, serviceDate: string): Promise<boolean> {
    return ensureMissingSchedule(pool, serviceDate, stops => generateSchedule(config, serviceDate, stops));
}

export async function generateScheduleForDate(
    client: QueryClient,
    config: ServiceConfig,
    serviceDate: string,
): Promise<GeneratedSchedule> {
    const stops = await loadScheduleStops(client);
    const schedule = generateSchedule(config, serviceDate, stops);
    await replaceSchedule(client, serviceDate, schedule);
    return schedule;
}
