import { sql } from 'drizzle-orm';
import type { TrainPositionRow } from '../domain/types.js';
import type { DatabaseExecutor } from './client.js';

export async function checkDatabase(db: DatabaseExecutor): Promise<void> {
    await db.execute(sql`SELECT 1`);
}

export async function loadTrainPositions(db: DatabaseExecutor, at: Date): Promise<TrainPositionRow[]> {
    const result = await db.execute<TrainPositionRow>(
        sql`SELECT * FROM mtr.train_positions(${at})`,
    );
    return result.rows;
}
