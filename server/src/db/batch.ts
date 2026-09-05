import type { Client } from 'pg';

type QueryClient = Pick<Client, 'query'>;

export async function insertRows(
    client: QueryClient,
    prefix: string,
    columnCount: number,
    rows: unknown[][],
): Promise<void> {
    const batchSize = 500;
    for (let start = 0; start < rows.length; start += batchSize) {
        const batch = rows.slice(start, start + batchSize);
        const parameters = batch.flat();
        const values = batch.map((_, rowIndex) => {
            const offset = rowIndex * columnCount;
            const placeholders = Array.from(
                { length: columnCount },
                (__, columnIndex) => `$${offset + columnIndex + 1}`,
            );
            return `(${placeholders.join(', ')})`;
        });
        await client.query(`${prefix} VALUES ${values.join(', ')}`, parameters);
    }
}
