import { asc } from 'drizzle-orm';
import type { Database } from './client.js';
import { stations } from './schema.js';

export interface StationCatalogRow {
    code: string;
    nameEn: string;
    nameZh: string;
    lineIds: string;
    interchange: boolean;
}

export function loadStationCatalog(db: Database): Promise<StationCatalogRow[]> {
    return db.select({
        code: stations.code,
        nameEn: stations.nameEn,
        nameZh: stations.nameZh,
        lineIds: stations.lineIds,
        interchange: stations.interchange,
    }).from(stations).orderBy(asc(stations.code));
}
