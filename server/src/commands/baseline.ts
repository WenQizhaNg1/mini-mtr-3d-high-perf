import { readMigrationFiles } from 'drizzle-orm/migrator';
import { Client } from 'pg';
import { validateBaselineSchema } from '../db/baseline.js';
import { MIGRATION_LOCK, migrationsFolder } from '../db/migrations.js';

const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error('DATABASE_URL is required');

const [initialMigration] = readMigrationFiles({ migrationsFolder });
if (!initialMigration) throw new Error('Initial migration is missing');
const checkOnly = process.argv.includes('--check');

const client = new Client({ connectionString });
try {
    await client.connect();
    await client.query('SELECT pg_advisory_lock(hashtext($1))', [MIGRATION_LOCK]);
    const journalExists = await client.query<{ exists: boolean }>(`
        SELECT to_regclass('drizzle.__drizzle_migrations') IS NOT NULL AS exists
    `);
    const existing = journalExists.rows[0]?.exists
        ? await client.query<{ hash: string; created_at: string }>(`
            SELECT hash, created_at
            FROM drizzle.__drizzle_migrations
            ORDER BY created_at
        `)
        : { rows: [] };
    if (existing.rows.length > 0) {
        const initial = existing.rows[0];
        if (
            initial?.hash !== initialMigration.hash
            || Number(initial.created_at) !== initialMigration.folderMillis
        ) {
            throw new Error('Migration journal does not start with the initial migration');
        }
        console.log('Initial migration is already recorded.');
    } else {
        await validateBaselineSchema(client);
        if (checkOnly) {
            console.log('Existing mtr schema matches the initial migration.');
        } else {
            await client.query('BEGIN');
            await client.query('CREATE SCHEMA IF NOT EXISTS drizzle');
            await client.query(`
                CREATE TABLE IF NOT EXISTS drizzle.__drizzle_migrations (
                    id serial PRIMARY KEY,
                    hash text NOT NULL,
                    created_at bigint
                )
            `);
            await client.query(`
                INSERT INTO drizzle.__drizzle_migrations (hash, created_at)
                VALUES ($1, $2)
            `, [initialMigration.hash, initialMigration.folderMillis]);
            console.log('Existing mtr schema validated and initial migration baselined.');
            await client.query('COMMIT');
        }
    }
} catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
} finally {
    await client.query('SELECT pg_advisory_unlock(hashtext($1))', [MIGRATION_LOCK]).catch(() => {});
    await client.end();
}
