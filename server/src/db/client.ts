import { drizzle, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { Pool } from 'pg';
import { databaseSchema } from './schema.js';

export type Database = NodePgDatabase<typeof databaseSchema>;
export type DatabaseExecutor = Pick<Database, 'execute'>;

export interface DatabaseConnection {
    db: Database;
    pool: Pool;
    close(): Promise<void>;
}

export function createDatabase(connectionString: string): DatabaseConnection {
    const pool = new Pool({ connectionString });
    const db = drizzle(pool, { schema: databaseSchema });
    return {
        db,
        pool,
        close: () => pool.end(),
    };
}
