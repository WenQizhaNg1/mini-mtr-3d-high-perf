import type { Client } from 'pg';
import type {
    NetworkData,
    NetworkState,
    RouteStopValue,
} from '../domain/network.js';

type QueryClient = Pick<Client, 'query'>;

export async function calculateRouteStops(
    client: QueryClient,
    network: NetworkData,
): Promise<RouteStopValue[]> {
    const result: RouteStopValue[] = [];
    for (const pattern of network.patterns) {
        const geom = JSON.stringify(pattern.geom);
        for (const [stopSequence, stop] of pattern.stops.entries()) {
            const fraction = await client.query<{ fraction: number }>(`
                SELECT ST_LineLocatePoint(
                    ST_SetSRID(ST_GeomFromGeoJSON($1), 4326),
                    ST_SetSRID(ST_MakePoint($2, $3), 4326)
                ) AS fraction
            `, [geom, stop.pathCoordinate[0], stop.pathCoordinate[1]]);
            result.push({
                patternId: pattern.id,
                stopSequence,
                stationCode: stop.stationCode,
                distanceM: stop.distanceM,
                fraction: fraction.rows[0]!.fraction,
            });
        }
    }
    return result;
}

export async function loadNetworkState(client: QueryClient): Promise<NetworkState> {
    const [patterns, stops] = await Promise.all([
        client.query<{ id: string }>('SELECT id FROM mtr.route_patterns ORDER BY id'),
        client.query<{
            pattern_id: string;
            stop_sequence: number;
            station_code: string;
            distance_m: number;
            fraction: number;
        }>(`
            SELECT pattern_id, stop_sequence, station_code, distance_m, fraction
            FROM mtr.route_stops
            ORDER BY pattern_id, stop_sequence
        `),
    ]);
    return {
        patternIds: patterns.rows.map(row => row.id),
        stops: stops.rows.map(row => ({
            patternId: row.pattern_id,
            stopSequence: row.stop_sequence,
            stationCode: row.station_code,
            distanceM: row.distance_m,
            fraction: row.fraction,
        })),
    };
}

export async function loadAffectedServiceDates(
    client: QueryClient,
    patternIds: string[],
): Promise<string[]> {
    if (patternIds.length === 0) return [];
    const placeholders = patternIds.map((_, index) => `$${index + 1}`).join(', ');
    const result = await client.query<{ service_date: string }>(`
        SELECT DISTINCT service_date::text AS service_date
        FROM mtr.train_runs
        WHERE pattern_id IN (${placeholders})
        ORDER BY service_date
    `, patternIds);
    return result.rows.map(row => row.service_date);
}

export async function replaceNetwork(
    client: QueryClient,
    network: NetworkData,
    routeStops: RouteStopValue[],
): Promise<void> {
    await client.query('DELETE FROM mtr.route_stops');

    const patternIds = network.patterns.map(pattern => pattern.id);
    const patternPlaceholders = patternIds.map((_, index) => `$${index + 1}`).join(', ');
    await client.query(
        `DELETE FROM mtr.route_patterns WHERE id NOT IN (${patternPlaceholders})`,
        patternIds,
    );
    for (const pattern of network.patterns) {
        await client.query(`
            INSERT INTO mtr.route_patterns (
                id, osm_relation_id, line_id, from_code, to_code, from_name,
                to_name, via, colour, length_m, stop_count, geom
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11,
                ST_SetSRID(ST_GeomFromGeoJSON($12), 4326)
            )
            ON CONFLICT (id) DO UPDATE SET
                osm_relation_id = EXCLUDED.osm_relation_id,
                line_id = EXCLUDED.line_id,
                from_code = EXCLUDED.from_code,
                to_code = EXCLUDED.to_code,
                from_name = EXCLUDED.from_name,
                to_name = EXCLUDED.to_name,
                via = EXCLUDED.via,
                colour = EXCLUDED.colour,
                length_m = EXCLUDED.length_m,
                stop_count = EXCLUDED.stop_count,
                geom = EXCLUDED.geom
        `, [
            pattern.id,
            pattern.osmRelationId,
            pattern.lineId,
            pattern.fromCode,
            pattern.toCode,
            pattern.fromName,
            pattern.toName,
            pattern.via,
            pattern.colour,
            pattern.lengthM,
            pattern.stopCount,
            JSON.stringify(pattern.geom),
        ]);
    }

    for (const station of network.stations) {
        await client.query(`
            INSERT INTO mtr.stations (
                code, name, name_en, name_zh, line_ids, interchange, geom
            ) VALUES (
                $1, $2, $3, $4, $5, $6,
                ST_SetSRID(ST_GeomFromGeoJSON($7), 4326)
            )
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                name_zh = EXCLUDED.name_zh,
                line_ids = EXCLUDED.line_ids,
                interchange = EXCLUDED.interchange,
                geom = EXCLUDED.geom
        `, [
            station.code,
            station.name,
            station.nameEn,
            station.nameZh,
            station.lineIds,
            station.interchange,
            JSON.stringify(station.geom),
        ]);
    }
    const stationCodes = network.stations.map(station => station.code);
    const stationPlaceholders = stationCodes.map((_, index) => `$${index + 1}`).join(', ');
    await client.query(
        `DELETE FROM mtr.stations WHERE code NOT IN (${stationPlaceholders})`,
        stationCodes,
    );

    for (const stop of routeStops) {
        await client.query(`
            INSERT INTO mtr.route_stops (
                pattern_id, stop_sequence, station_code, distance_m, fraction
            ) VALUES ($1, $2, $3, $4, $5)
        `, [
            stop.patternId,
            stop.stopSequence,
            stop.stationCode,
            stop.distanceM,
            stop.fraction,
        ]);
    }

    await client.query('ANALYZE mtr.route_patterns');
    await client.query('ANALYZE mtr.stations');
    await client.query('ANALYZE mtr.route_stops');
}

export async function validateNetwork(
    client: QueryClient,
): Promise<{
    routePatterns: number;
    stations: number;
    routeStops: number;
    invalidGeometries: number;
}> {
    const result = await client.query<{
        route_patterns: number;
        stations: number;
        route_stops: number;
        invalid_geometries: number;
    }>(`
        SELECT
            (SELECT count(*)::integer FROM mtr.route_patterns) AS route_patterns,
            (SELECT count(*)::integer FROM mtr.stations) AS stations,
            (SELECT count(*)::integer FROM mtr.route_stops) AS route_stops,
            (SELECT count(*)::integer FROM mtr.route_patterns WHERE NOT ST_IsValid(geom))
                + (SELECT count(*)::integer FROM mtr.stations WHERE NOT ST_IsValid(geom))
                AS invalid_geometries
    `);
    const row = result.rows[0]!;
    return {
        routePatterns: row.route_patterns,
        stations: row.stations,
        routeStops: row.route_stops,
        invalidGeometries: row.invalid_geometries,
    };
}
