import type { Map as MapLibreMap, CircleLayerSpecification, ExpressionSpecification, FillExtrusionLayerSpecification, LineLayerSpecification, SymbolLayerSpecification } from 'maplibre-gl';
import { routeWidthExpression, ROUTE_CASING_RATIO } from './dimensions';
export const MTR_SOURCE_ID = 'mtr';
export const TRAIN_SOURCE_ID = 'mtr-trains';
export const TRAIN_COLOUR: ExpressionSpecification = ['interpolate', ['linear'], 0.22, 0, ['get', 'colour'], 1, '#ffffff'];

const routeCasing: LineLayerSpecification = {
    id: 'mtr-route-casing',
    type: 'line',
    source: MTR_SOURCE_ID,
    'source-layer': 'mtr_routes',
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
        'line-color': '#ffffff',
        'line-width': routeWidthExpression(ROUTE_CASING_RATIO),
    },
};

const routes: LineLayerSpecification = {
    id: 'mtr-routes',
    type: 'line',
    source: MTR_SOURCE_ID,
    'source-layer': 'mtr_routes',
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
        'line-color': ['coalesce', ['get', 'colour'], '#64748b'],
        'line-width': routeWidthExpression(),
    },
};

const trains: FillExtrusionLayerSpecification = {
    id: 'mtr-trains',
    type: 'fill-extrusion',
    source: TRAIN_SOURCE_ID,
    paint: {
        'fill-extrusion-color': TRAIN_COLOUR,
        'fill-extrusion-height': ['get', 'height'],
        'fill-extrusion-opacity': 1,
        'fill-extrusion-vertical-gradient': true,
    },
};

const stations: CircleLayerSpecification = {
    id: 'mtr-stations',
    type: 'circle',
    source: MTR_SOURCE_ID,
    'source-layer': 'mtr_stations',
    paint: {
        'circle-color': '#ffffff',
        'circle-radius': [
            'interpolate', ['linear'], ['zoom'],
            9, ['case', ['get', 'interchange'], 3, 2],
            11, ['case', ['get', 'interchange'], 5, 3.5],
            14, ['case', ['get', 'interchange'], 17, 13],
            18, ['case', ['get', 'interchange'], 42, 32],
            22, ['case', ['get', 'interchange'], 120, 96],
        ],
        'circle-stroke-color': '#111827',
        'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 9, 1.5, 14, 3, 18, 5, 22, 8],
        'circle-pitch-alignment': 'map',
        'circle-pitch-scale': 'map',
    },
};

const stationLabels: SymbolLayerSpecification = {
    id: 'mtr-station-labels',
    type: 'symbol',
    source: MTR_SOURCE_ID,
    'source-layer': 'mtr_stations',
    minzoom: 10.5,
    layout: {
        'text-field': [
            'format',
            ['get', 'name_zh'], {},
            '\n', {},
            ['get', 'name_en'], { 'font-scale': 0.78 },
        ],
        'text-font': ['Noto Sans CJK SC Bold'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 10.5, 12, 14, 16, 18, 18],
        'text-offset': ['interpolate', ['linear'], ['zoom'], 10.5, ['literal', [0, 1.1]],
            14, ['literal', [0, 1.6]], 18, ['literal', [0, 3]], 22, ['literal', [0, 7.5]]],
        'text-anchor': 'top',
        'text-optional': true,
        'text-padding': 3,
    },
    paint: {
        'text-color': '#343b43',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.5,
        'text-halo-blur': 0,
    },
};

export function addMtrLayers(map: MapLibreMap, dark: boolean, martinUrl: string) {
    map.addSource(MTR_SOURCE_ID, {
        type: 'vector',
        url: `${martinUrl}/mtr-routes,mtr-stations`,
    });
    map.addSource(TRAIN_SOURCE_ID, {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
    });
    const buildingLayer = map.getStyle().layers.find(layer => layer.type === 'fill-extrusion')?.id;
    map.addLayer(routeCasing, buildingLayer);
    map.addLayer(routes, buildingLayer);
    map.addLayer(stations);
    map.addLayer(trains);
    map.addLayer(stationLabels);
    if (dark) {
        map.setLight({ anchor: 'viewport', color: '#ffffff', intensity: 0.4, position: [1.5, 210, 40] });
        map.setPaintProperty('mtr-route-casing', 'line-color', '#283341');
        map.setPaintProperty('mtr-station-labels', 'text-color', '#f1f5f9');
        map.setPaintProperty('mtr-station-labels', 'text-halo-color', '#18222f');
    }
}
