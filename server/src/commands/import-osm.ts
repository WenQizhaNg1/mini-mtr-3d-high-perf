import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from 'pg';
import { readServiceConfig } from '../config.js';
import { NETWORK_DATA_LOCK } from '../db/locks.js';
import { readNetworkData } from '../integrations/osm-files.js';
import { importNetwork } from '../services/network-import-service.js';

const connectionString = process.env.DATABASE_URL;
if (!connectionString) throw new Error('DATABASE_URL is required');

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const args = process.argv.slice(2);
const allowPatternChanges = args.includes('--allow-pattern-changes');
const positional = args.filter(argument => !argument.startsWith('-'));
const unknownOptions = args.filter(argument => argument.startsWith('-') && argument !== '--allow-pattern-changes');
if (positional.length > 1 || unknownOptions.length > 0) {
    throw new Error('Usage: import-osm [generated-directory] [--allow-pattern-changes]');
}

const generatedDir = resolve(repoRoot, positional[0] ?? 'data/osm/generated');
const network = readNetworkData(generatedDir);
const config = readServiceConfig();
const client = new Client({ connectionString });

try {
    await client.connect();
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [NETWORK_DATA_LOCK]);
    const result = await importNetwork(client, network, config, allowPatternChanges);
    await client.query('COMMIT');

    console.log(
        `Imported ${result.routePatterns} route patterns, ${result.routeStops} route stops, `
        + `and ${result.stations} stations into schema mtr.`,
    );
    if (result.regeneratedServiceDates.length > 0) {
        console.log(`Regenerated service dates: ${result.regeneratedServiceDates.join(', ')}.`);
    }
} catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
} finally {
    await client.end();
}
