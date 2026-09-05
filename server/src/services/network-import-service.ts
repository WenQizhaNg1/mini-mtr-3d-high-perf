import type { Client } from 'pg';
import {
    calculateRouteStops,
    loadAffectedServiceDates,
    loadNetworkState,
    replaceNetwork,
    validateNetwork,
} from '../db/network-queries.js';
import { deleteSchedules } from '../db/schedule-queries.js';
import { findChangedPatternIds, type NetworkData } from '../domain/network.js';
import type { ServiceConfig } from '../domain/types.js';
import { generateScheduleForDate } from './schedule-service.js';

type QueryClient = Pick<Client, 'query'>;

export class UnsafeNetworkChangeError extends Error {
    constructor(
        readonly patternIds: string[],
        readonly serviceDates: string[],
    ) {
        super(
            `Route stops changed for patterns: ${patternIds.join(', ')}. `
            + `Affected service dates: ${serviceDates.join(', ')}. `
            + 'Re-run with --allow-pattern-changes to replace the network and regenerate those dates.',
        );
        this.name = 'UnsafeNetworkChangeError';
    }
}

export interface NetworkImportResult {
    routePatterns: number;
    stations: number;
    routeStops: number;
    changedPatternIds: string[];
    regeneratedServiceDates: string[];
}

function validateConfiguredPatterns(network: NetworkData, config: ServiceConfig): void {
    const available = new Set(network.patterns.map(pattern => pattern.id));
    const lines = new Map(config.lines.map(line => [line.lineId, line]));
    for (const pattern of network.patterns) {
        const line = lines.get(pattern.lineId);
        if (!line) throw new Error(`Route pattern ${pattern.id} references unknown line ${pattern.lineId}`);
        if (pattern.colour.toLowerCase() !== line.colour.toLowerCase()) {
            throw new Error(`Route pattern ${pattern.id} colour does not match service config`);
        }
    }
    for (const line of config.lines) {
        for (const direction of ['UP', 'DOWN'] as const) {
            const choice = line.patterns[direction];
            for (const patternId of [choice.default, choice.alternate]) {
                if (patternId && !available.has(patternId)) {
                    throw new Error(`Service config references missing route pattern ${patternId}`);
                }
            }
        }
    }
}

export async function importNetwork(
    client: QueryClient,
    network: NetworkData,
    config: ServiceConfig,
    allowPatternChanges: boolean,
): Promise<NetworkImportResult> {
    validateConfiguredPatterns(network, config);
    const incomingStops = await calculateRouteStops(client, network);
    const current = await loadNetworkState(client);
    const changedPatternIds = findChangedPatternIds(current, {
        patternIds: network.patterns.map(pattern => pattern.id),
        stops: incomingStops,
    });
    const affectedServiceDates = await loadAffectedServiceDates(client, changedPatternIds);

    if (affectedServiceDates.length > 0 && !allowPatternChanges) {
        throw new UnsafeNetworkChangeError(changedPatternIds, affectedServiceDates);
    }

    await deleteSchedules(client, affectedServiceDates);
    await replaceNetwork(client, network, incomingStops);

    for (const serviceDate of affectedServiceDates) {
        await generateScheduleForDate(client, config, serviceDate);
    }

    const counts = await validateNetwork(client);
    const expectedStops = network.patterns.reduce(
        (count, pattern) => count + pattern.stops.length,
        0,
    );
    if (
        counts.routePatterns !== network.patterns.length
        || counts.stations !== network.stations.length
        || counts.routeStops !== expectedStops
        || counts.invalidGeometries !== 0
    ) {
        throw new Error(`Import validation failed: ${JSON.stringify(counts)}`);
    }

    return {
        routePatterns: counts.routePatterns,
        stations: counts.stations,
        routeStops: counts.routeStops,
        changedPatternIds,
        regeneratedServiceDates: affectedServiceDates,
    };
}
