import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Client } from 'pg';
import { MIGRATION_LOCK, migrationsFolder } from '../db/migrations.js';

const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error('DATABASE_URL is required');

const client = new Client({ connectionString });
try {
    await client.connect();
    await client.query('SELECT pg_advisory_lock(hashtext($1))', [MIGRATION_LOCK]);
    await migrate(drizzle(client), { migrationsFolder });
    console.log('Database migrations are up to date.');
} finally {
    await client.query('SELECT pg_advisory_unlock(hashtext($1))', [MIGRATION_LOCK]).catch(() => {});
    await client.end();
}
