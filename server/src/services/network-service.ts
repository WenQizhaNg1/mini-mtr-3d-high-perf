import type { Database } from '../db/client.js';
import { loadStationCatalog } from '../db/catalog-queries.js';
import type { NetworkCatalog, ServiceConfig } from '../domain/types.js';

export interface NetworkService {
    loadCatalog(): Promise<NetworkCatalog>;
}

export function createNetworkService(db: Database, config: ServiceConfig): NetworkService {
    return {
        async loadCatalog() {
            const stations = await loadStationCatalog(db);
            return {
                service: {
                    serviceDayStart: config.serviceDayStart,
                    serviceEndOffsetMinutes: config.serviceEndOffsetMinutes,
                },
                lines: config.lines.map(line => ({
                    id: line.lineId,
                    apiCode: line.apiCode,
                    nameEn: line.nameEn,
                    nameZh: line.nameZh,
                    colour: line.colour,
                    incidentAnchorStation: line.incidentAnchorStation,
                })),
                stations: stations.map(station => ({
                    code: station.code,
                    nameEn: station.nameEn,
                    nameZh: station.nameZh,
                    interchange: station.interchange,
                    lineIds: station.lineIds.split(',').filter(Boolean),
                })),
            };
        },
    };
}
