import { sql } from 'drizzle-orm';
import type { DatabaseExecutor } from './client.js';

interface ServiceDayDatabaseState extends Record<string, unknown> {
    replay_starts_at: Date | string | null;
    replay_ends_at: Date | string | null;
    current_exists: boolean;
    next_exists: boolean;
}

export async function loadServiceDayDatabaseState(
    db: DatabaseExecutor,
    currentServiceDate: string,
    nextServiceDate: string,
): Promise<ServiceDayDatabaseState> {
    const result = await db.execute<ServiceDayDatabaseState>(sql`
        SELECT
            MIN(starts_at) AS replay_starts_at,
            MAX(ends_at) AS replay_ends_at,
            EXISTS (
                SELECT 1 FROM mtr.train_runs WHERE service_date = ${currentServiceDate}
            ) AS current_exists,
            EXISTS (
                SELECT 1 FROM mtr.train_runs WHERE service_date = ${nextServiceDate}
            ) AS next_exists
        FROM mtr.train_runs
    `);
    return result.rows[0]!;
}
