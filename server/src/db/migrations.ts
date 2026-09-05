import { fileURLToPath } from 'node:url';

export const MIGRATION_LOCK = 'mini-mtr-schema-migrations';
export const migrationsFolder = fileURLToPath(new URL('../../migrations/', import.meta.url));
