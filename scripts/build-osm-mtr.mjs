import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { XMLParser } from 'fast-xml-parser';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = resolve(repoRoot, process.argv[2] || 'data/osm/hk-mtr-2026-09-04.osm');
const outputDir = resolve(repoRoot, process.argv[3] || 'data/osm/generated');

const ENDPOINT_PAIRS = [
    ['ISL', 'KET', 'CHW'],
    ['TWL', 'CEN', 'TSW'],
    ['KTL', 'WHA', 'TIK'],
    ['TKL', 'NOP', 'POA'],
    ['TKL', 'NOP', 'LHP'],
    ['EAL', 'ADM', 'LOW'],
    ['EAL', 'ADM', 'LMC'],
    ['TML', 'WKS', 'TUM'],
    ['TCL', 'HOK', 'TUC'],
    ['AEL', 'HOK', 'AWE'],
    ['DRL', 'SUN', 'DIS'],
    ['SIL', 'ADM', 'SOH'],
];

// Reviewed differences between OSM stop_position tags and MTR API station codes.
const STOP_CODE_OVERRIDES = new Map([
    ['3304026514', 'AWE'],
    ['2179557425', 'ETS'],
    ['384143704', 'ETS'],
    ['2308404918', 'SUW'],
    ['2308405006', 'SUW'],
    ['307394049', 'TIK'],
    ['307394050', 'TIK'],
    ['4331931870', 'TIK'],
    ['984741544', 'TIK'],
]);

const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: '',
    parseAttributeValue: false,
    isArray: name => ['node', 'way', 'relation', 'nd', 'member', 'tag'].includes(name),
});

function tagsOf(element) {
    return Object.fromEntries((element.tag || []).map(tag => [tag.k, tag.v]));
}

function fail(message) {
    throw new Error(message);
}

function radians(value) {
    return value * Math.PI / 180;
}

function distanceMeters(a, b) {
    const dLat = radians(b[1] - a[1]);
    const dLng = radians(b[0] - a[0]);
    const latA = radians(a[1]);
    const latB = radians(b[1]);
    const h = Math.sin(dLat / 2) ** 2
        + Math.cos(latA) * Math.cos(latB) * Math.sin(dLng / 2) ** 2;
    return 12_742_000 * Math.asin(Math.sqrt(h));
}

function roundCoordinate([lng, lat]) {
    return [Number(lng.toFixed(7)), Number(lat.toFixed(7))];
}

function roundMeters(value) {
    return Number(value.toFixed(2));
}

const xml = parser.parse(readFileSync(sourcePath, 'utf8')).osm;
const osmBase = xml.meta?.osm_base || '';

const nodes = new Map((xml.node || []).map(raw => [raw.id, {
    id: raw.id,
    coordinate: [Number(raw.lon), Number(raw.lat)],
    tags: tagsOf(raw),
}]));

const ways = new Map((xml.way || []).map(raw => [raw.id, {
    id: raw.id,
    nodeIds: (raw.nd || []).map(node => node.ref),
    tags: tagsOf(raw),
}]));

const relations = (xml.relation || []).map(raw => ({
    id: raw.id,
    members: (raw.member || []).map(member => ({
        type: member.type,
        ref: member.ref,
        role: member.role || '',
    })),
    tags: tagsOf(raw),
}));

function nodeCoordinate(nodeId) {
    const node = nodes.get(nodeId);
    if (!node) fail(`Missing node ${nodeId}`);
    return node.coordinate;
}

function stopCode(node) {
    return STOP_CODE_OVERRIDES.get(node.id) || node.tags.ref || '';
}

function routeStops(relation) {
    return relation.members
        .filter(member => member.type === 'node' && member.role.startsWith('stop'))
        .map(member => {
            const node = nodes.get(member.ref);
            if (!node) fail(`Relation ${relation.id} references missing stop node ${member.ref}`);
            const code = stopCode(node);
            if (!code) fail(`Stop node ${node.id} (${node.tags.name || 'unnamed'}) has no station code`);
            return { node, code, role: member.role };
        });
}

function buildRouteNodeChain(relation) {
    const routeWays = relation.members
        .filter(member => member.type === 'way' && member.role === '')
        .map(member => {
            const way = ways.get(member.ref);
            if (!way) fail(`Relation ${relation.id} references missing way ${member.ref}`);
            if (way.nodeIds.length < 2) fail(`Route way ${way.id} has fewer than two nodes`);
            return way;
        });

    if (routeWays.length === 0) fail(`Relation ${relation.id} has no travel ways`);

    const first = [...routeWays[0].nodeIds];
    if (routeWays.length > 1) {
        const second = routeWays[1].nodeIds;
        const secondEnds = new Set([second[0], second.at(-1)]);
        if (!secondEnds.has(first.at(-1))) {
            if (!secondEnds.has(first[0])) {
                fail(`Relation ${relation.id} has a gap after way ${routeWays[0].id}`);
            }
            first.reverse();
        }
    }

    const chain = [...first];
    for (let index = 1; index < routeWays.length; index++) {
        const nodeIds = [...routeWays[index].nodeIds];
        if (nodeIds.at(-1) === chain.at(-1)) nodeIds.reverse();
        if (nodeIds[0] !== chain.at(-1)) {
            fail(`Relation ${relation.id} has a gap before way ${routeWays[index].id}`);
        }
        chain.push(...nodeIds.slice(1));
    }

    return { chain, routeWays };
}

function nearestChainIndex(chain, stop, startIndex) {
    for (let index = startIndex; index < chain.length; index++) {
        if (chain[index] === stop.node.id) return { index, snapDistanceMeters: 0 };
    }

    let bestIndex = -1;
    let bestDistance = Infinity;
    for (let index = startIndex; index < chain.length; index++) {
        const distance = distanceMeters(stop.node.coordinate, nodeCoordinate(chain[index]));
        if (distance < bestDistance) {
            bestIndex = index;
            bestDistance = distance;
        }
    }
    return { index: bestIndex, snapDistanceMeters: bestDistance };
}

function relationPattern(relation) {
    const stops = routeStops(relation);
    const { chain, routeWays } = buildRouteNodeChain(relation);
    const matchedStops = [];
    let searchFrom = 0;

    for (const stop of stops) {
        const match = nearestChainIndex(chain, stop, searchFrom);
        if (match.index < 0 || match.snapDistanceMeters > 10) {
            fail(`Stop ${stop.code} is ${match.snapDistanceMeters.toFixed(2)} m from route ${relation.id}`);
        }
        matchedStops.push({ ...stop, ...match });
        searchFrom = match.index;
    }

    const startIndex = matchedStops[0].index;
    const endIndex = matchedStops.at(-1).index;
    if (endIndex <= startIndex) fail(`Relation ${relation.id} has reversed or empty stop extent`);

    const trimmedNodeIds = chain.slice(startIndex, endIndex + 1);
    const coordinates = trimmedNodeIds.map(id => roundCoordinate(nodeCoordinate(id)));
    const cumulativeMeters = [0];
    for (let index = 1; index < coordinates.length; index++) {
        cumulativeMeters.push(
            cumulativeMeters.at(-1) + distanceMeters(coordinates[index - 1], coordinates[index]),
        );
    }

    const normalizedStops = matchedStops.map(stop => {
        const pathIndex = stop.index - startIndex;
        const tags = stop.node.tags;
        return {
            code: stop.code,
            osmNodeId: stop.node.id,
            role: stop.role,
            name: tags.name || '',
            nameEn: tags['name:en'] || '',
            nameZh: tags['name:zh-Hant'] || tags['name:zh'] || tags['name:yue'] || '',
            coordinate: roundCoordinate(stop.node.coordinate),
            pathCoordinate: coordinates[pathIndex],
            distanceMeters: roundMeters(cumulativeMeters[pathIndex]),
            snapDistanceMeters: roundMeters(stop.snapDistanceMeters),
        };
    });

    return {
        relation,
        fromCode: normalizedStops[0].code,
        toCode: normalizedStops.at(-1).code,
        coordinates,
        stops: normalizedStops,
        lengthMeters: roundMeters(cumulativeMeters.at(-1)),
        deprecatedWayIds: routeWays
            .filter(way => ['disused', 'razed'].includes(way.tags.railway))
            .map(way => way.id),
    };
}

const subwayRelations = relations.filter(relation =>
    relation.tags.type === 'route'
    && relation.tags.route === 'subway'
    && ENDPOINT_PAIRS.some(([lineId]) => lineId === relation.tags.ref),
);

const candidates = subwayRelations.map(relationPattern);
const selected = [];

for (const [lineId, endpointA, endpointB] of ENDPOINT_PAIRS) {
    for (const [fromCode, toCode] of [[endpointA, endpointB], [endpointB, endpointA]]) {
        const matches = candidates.filter(candidate =>
            candidate.relation.tags.ref === lineId
            && candidate.fromCode === fromCode
            && candidate.toCode === toCode
            && !/Racecourse|馬場/i.test(candidate.relation.tags.via || ''),
        );
        if (matches.length !== 1) {
            fail(`Expected one ${lineId} pattern ${fromCode}->${toCode}, found ${matches.length}`);
        }
        selected.push(matches[0]);
    }
}

const patternIds = new Set();
const patterns = selected.map(candidate => {
    if (candidate.deprecatedWayIds.length > 0) {
        fail(`Selected relation ${candidate.relation.id} contains deprecated railway ways`);
    }
    const { relation, fromCode, toCode, coordinates, stops, lengthMeters } = candidate;
    const id = `${relation.tags.ref}:${fromCode}-${toCode}`;
    if (patternIds.has(id)) fail(`Duplicate pattern ID ${id}`);
    patternIds.add(id);
    return {
        id,
        osmRelationId: relation.id,
        lineId: relation.tags.ref,
        fromCode,
        toCode,
        from: relation.tags.from || '',
        to: relation.tags.to || '',
        via: relation.tags.via || '',
        colour: relation.tags.colour || '',
        lengthMeters,
        geometry: { type: 'LineString', coordinates },
        stops,
    };
});

if (patterns.length !== 24) fail(`Expected 24 route patterns, found ${patterns.length}`);

const stationGroups = new Map();
for (const candidate of candidates) {
    for (const stop of candidate.stops) {
        let group = stationGroups.get(stop.code);
        if (!group) {
            group = { code: stop.code, stops: new Map(), lineIds: new Set(), patternIds: new Set() };
            stationGroups.set(stop.code, group);
        }
        group.stops.set(stop.osmNodeId, stop);
        group.lineIds.add(candidate.relation.tags.ref);
    }
}

for (const pattern of patterns) {
    for (const stop of pattern.stops) {
        const group = stationGroups.get(stop.code);
        group.patternIds.add(pattern.id);
    }
}

if (stationGroups.size !== 98) fail(`Expected 98 stations, found ${stationGroups.size}`);

const stations = [...stationGroups.values()]
    .map(group => {
        const stopList = [...group.stops.values()];
        const coordinate = [
            stopList.reduce((sum, stop) => sum + stop.coordinate[0], 0) / stopList.length,
            stopList.reduce((sum, stop) => sum + stop.coordinate[1], 0) / stopList.length,
        ];
        return {
            code: group.code,
            name: stopList.find(stop => stop.name)?.name || group.code,
            nameEn: stopList.find(stop => stop.nameEn)?.nameEn || '',
            nameZh: stopList.find(stop => stop.nameZh)?.nameZh || '',
            coordinate: roundCoordinate(coordinate),
            lineIds: [...group.lineIds].sort(),
            patternIds: [...group.patternIds].sort(),
            osmStopNodeIds: [...group.stops.keys()].sort((a, b) => Number(a) - Number(b)),
        };
    })
    .sort((a, b) => a.code.localeCompare(b.code));

const source = {
    file: sourcePath.slice(repoRoot.length + 1).replaceAll('\\', '/'),
    osmBase,
    license: 'ODbL',
};

const routeData = { schemaVersion: 1, source, patterns };
const routeGeoJson = {
    type: 'FeatureCollection',
    features: patterns.map(pattern => ({
        type: 'Feature',
        id: pattern.id,
        properties: {
            id: pattern.id,
            osm_relation_id: pattern.osmRelationId,
            line_id: pattern.lineId,
            from_code: pattern.fromCode,
            to_code: pattern.toCode,
            from: pattern.from,
            to: pattern.to,
            via: pattern.via,
            colour: pattern.colour,
            length_m: pattern.lengthMeters,
            stop_count: pattern.stops.length,
        },
        geometry: pattern.geometry,
    })),
};
const stationGeoJson = {
    type: 'FeatureCollection',
    features: stations.map(station => ({
        type: 'Feature',
        id: station.code,
        properties: {
            code: station.code,
            name: station.name,
            name_en: station.nameEn,
            name_zh: station.nameZh,
            line_ids: station.lineIds.join(','),
            interchange: station.lineIds.length > 1,
        },
        geometry: { type: 'Point', coordinates: station.coordinate },
    })),
};

mkdirSync(outputDir, { recursive: true });
writeFileSync(resolve(outputDir, 'mtr-route-patterns.json'), `${JSON.stringify(routeData, null, 2)}\n`);
writeFileSync(resolve(outputDir, 'mtr-route-patterns.geojson'), `${JSON.stringify(routeGeoJson, null, 2)}\n`);
writeFileSync(resolve(outputDir, 'mtr-stations.geojson'), `${JSON.stringify(stationGeoJson, null, 2)}\n`);

const maxSnap = Math.max(...patterns.flatMap(pattern => pattern.stops.map(stop => stop.snapDistanceMeters)));
const lineCount = new Set(patterns.map(pattern => pattern.lineId)).size;
console.log(`Built ${patterns.length} route patterns for ${lineCount} lines.`);
console.log(`Built ${stations.length} logical stations; maximum stop snap is ${maxSnap.toFixed(2)} m.`);
console.log(`Output: ${outputDir}`);
