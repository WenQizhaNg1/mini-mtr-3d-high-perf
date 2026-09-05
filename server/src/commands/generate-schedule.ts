import { Client } from 'pg';
import { readServiceConfig } from '../config.js';
import { NETWORK_DATA_LOCK } from '../db/locks.js';
import { serviceDateAt } from '../domain/time.js';
import { generateScheduleForDate } from '../services/schedule-service.js';

const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error('DATABASE_URL is required');

const config = readServiceConfig();
const args = process.argv.slice(2);
if (args.length > 1 || args[0]?.startsWith('-')) {
    throw new Error('Usage: generate-schedule [YYYY-MM-DD]');
}
const serviceDate = args[0] ?? serviceDateAt(new Date(), config.serviceDayStart);

const client = new Client({ connectionString });
try {
    await client.connect();
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [NETWORK_DATA_LOCK]);
    const schedule = await generateScheduleForDate(client, config, serviceDate);
    await client.query('COMMIT');
    console.log(
        `Generated ${schedule.runs.length} train runs and ${schedule.legs.length} legs `
        + `for ${serviceDate}.`,
    );
} catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
} finally {
    await client.end();
}
