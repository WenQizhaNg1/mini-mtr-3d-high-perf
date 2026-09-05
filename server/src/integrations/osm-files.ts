import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type {
    LineStringGeometry,
    NetworkData,
    PointGeometry,
    RoutePatternInput,
    StationInput,
} from '../domain/network.js';

const EXPECTED_PATTERNS = 24;
const EXPECTED_STATIONS = 98;

type JsonObject = Record<string, unknown>;

function object(value: unknown, label: string): JsonObject {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
    return value as JsonObject;
}

function array(value: unknown, label: string): unknown[] {
    if (!Array.isArray(value)) throw new Error(`${label} must be an array`);
    return value;
}

function string(value: unknown, label: string): string {
    if (typeof value !== 'string') throw new Error(`${label} must be a string`);
    return value;
}

function number(value: unknown, label: string): number {
    const result = typeof value === 'number' ? value : Number(value);
    if (!Number.isFinite(result)) throw new Error(`${label} must be a finite number`);
    return result;
}

function coordinate(value: unknown, label: string): [number, number] {
    const values = array(value, label);
    if (values.length !== 2) throw new Error(`${label} must contain longitude and latitude`);
    return [number(values[0], `${label}[0]`), number(values[1], `${label}[1]`)];
}

function geometry(value: unknown, type: 'LineString'): LineStringGeometry;
function geometry(value: unknown, type: 'Point'): PointGeometry;
function geometry(value: unknown, type: 'LineString' | 'Point'): LineStringGeometry | PointGeometry {
    const raw = object(value, 'feature.geometry');
    if (raw.type !== type) throw new Error(`Expected ${type} geometry`);
    if (type === 'Point') {
        return { type, coordinates: coordinate(raw.coordinates, 'Point coordinates') };
    }
    const coordinates = array(raw.coordinates, 'LineString coordinates')
        .map((value, index) => coordinate(value, `LineString coordinates[${index}]`));
    if (coordinates.length < 2) throw new Error('LineString must contain at least two coordinates');
    return { type, coordinates };
}

function readJson(path: string): unknown {
    return JSON.parse(readFileSync(path, 'utf8')) as unknown;
}

function featureCollection(path: string, expectedCount: number): JsonObject[] {
    const document = object(readJson(path), path);
    if (document.type !== 'FeatureCollection') {
        throw new Error(`${path} is not a GeoJSON FeatureCollection`);
    }
    const features = array(document.features, `${path}.features`).map((value, index) => {
        const feature = object(value, `${path}.features[${index}]`);
        if (feature.type !== 'Feature') throw new Error(`${path} contains an invalid feature`);
        return feature;
    });
    if (features.length !== expectedCount) {
        throw new Error(`${path} contains ${features.length} features; expected ${expectedCount}`);
    }
    return features;
}

function unique(values: string[], label: string): void {
    if (new Set(values).size !== values.length) throw new Error(`${label} contains duplicate identifiers`);
}

export function readNetworkData(generatedDir: string): NetworkData {
    const routeFeatures = featureCollection(
        resolve(generatedDir, 'mtr-route-patterns.geojson'),
        EXPECTED_PATTERNS,
    );
    const stationFeatures = featureCollection(
        resolve(generatedDir, 'mtr-stations.geojson'),
        EXPECTED_STATIONS,
    );
    const routeDocument = object(
        readJson(resolve(generatedDir, 'mtr-route-patterns.json')),
        'mtr-route-patterns.json',
    );
    if (routeDocument.schemaVersion !== 1) {
        throw new Error('mtr-route-patterns.json has an unsupported schema');
    }
    const routeRecords = array(routeDocument.patterns, 'mtr-route-patterns.json.patterns');
    if (routeRecords.length !== EXPECTED_PATTERNS) {
        throw new Error(`mtr-route-patterns.json contains ${routeRecords.length} patterns; expected ${EXPECTED_PATTERNS}`);
    }
    const recordsById = new Map(routeRecords.map((value, index) => {
        const record = object(value, `patterns[${index}]`);
        return [string(record.id, `patterns[${index}].id`), record] as const;
    }));
    if (recordsById.size !== routeRecords.length) {
        throw new Error('mtr-route-patterns.json contains duplicate pattern identifiers');
    }

    const patterns: RoutePatternInput[] = routeFeatures.map((feature, index) => {
        const properties = object(feature.properties, `route feature ${index}.properties`);
        const id = string(properties.id, `route feature ${index}.id`);
        const record = recordsById.get(id);
        if (!record) throw new Error(`Route pattern ${id} is missing from mtr-route-patterns.json`);
        const stops = array(record.stops, `${id}.stops`).map((value, stopSequence) => {
            const stop = object(value, `${id}.stops[${stopSequence}]`);
            return {
                stationCode: string(stop.code, `${id}.stops[${stopSequence}].code`),
                distanceM: number(stop.distanceMeters, `${id}.stops[${stopSequence}].distanceMeters`),
                pathCoordinate: coordinate(
                    stop.pathCoordinate,
                    `${id}.stops[${stopSequence}].pathCoordinate`,
                ),
            };
        });
        const stopCount = number(properties.stop_count, `${id}.stop_count`);
        if (stops.length !== stopCount) {
            throw new Error(`Route pattern ${id} has ${stops.length} stops; expected ${stopCount}`);
        }
        return {
            id,
            osmRelationId: number(properties.osm_relation_id, `${id}.osm_relation_id`),
            lineId: string(properties.line_id, `${id}.line_id`),
            fromCode: string(properties.from_code, `${id}.from_code`),
            toCode: string(properties.to_code, `${id}.to_code`),
            fromName: string(properties.from, `${id}.from`),
            toName: string(properties.to, `${id}.to`),
            via: string(properties.via, `${id}.via`),
            colour: string(properties.colour, `${id}.colour`),
            lengthM: number(properties.length_m, `${id}.length_m`),
            stopCount,
            geom: geometry(feature.geometry, 'LineString'),
            stops,
        };
    });
    unique(patterns.map(pattern => pattern.id), 'Route patterns');
    if (patterns.some(pattern => !recordsById.has(pattern.id))) {
        throw new Error('Route GeoJSON and route-pattern JSON identifiers do not match');
    }

    const stations: StationInput[] = stationFeatures.map((feature, index) => {
        const properties = object(feature.properties, `station feature ${index}.properties`);
        const code = string(properties.code, `station feature ${index}.code`);
        if (typeof properties.interchange !== 'boolean') {
            throw new Error(`Station ${code}.interchange must be a boolean`);
        }
        return {
            code,
            name: string(properties.name, `${code}.name`),
            nameEn: string(properties.name_en, `${code}.name_en`),
            nameZh: string(properties.name_zh, `${code}.name_zh`),
            lineIds: string(properties.line_ids, `${code}.line_ids`),
            interchange: properties.interchange,
            geom: geometry(feature.geometry, 'Point'),
        };
    });
    unique(stations.map(station => station.code), 'Stations');

    const stationCodes = new Set(stations.map(station => station.code));
    for (const pattern of patterns) {
        for (const stop of pattern.stops) {
            if (!stationCodes.has(stop.stationCode)) {
                throw new Error(`Route pattern ${pattern.id} references missing station ${stop.stationCode}`);
            }
        }
    }

    return { patterns, stations };
}
