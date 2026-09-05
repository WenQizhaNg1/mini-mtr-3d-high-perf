import {
    GeoJSONSource,
    Map as MapLibreMap,
    NavigationControl,
    ScaleControl,
    type CircleLayerSpecification,
    type ErrorEvent,
    type ExpressionSpecification,
    type FillExtrusionLayerSpecification,
    type LineLayerSpecification,
    type SymbolLayerSpecification,
} from 'maplibre-gl';
import type { LngLatBoundsLike } from 'maplibre-gl';
import type { TrainPosition, TrainSnapshot } from './api';
import { trainFeatures } from './train-geometry';
import { mergeMotion, sampleMotion } from './train-motion';

const MARTIN_URL = (import.meta.env.VITE_MARTIN_URL || 'http://127.0.0.1:8081').replace(/\/$/, '');
const MTR_SOURCE_ID = 'mtr';
const TRAIN_SOURCE_ID = 'mtr-trains';
const TRAIN_TRANSITION_MS = 1000;
const TRAIN_COLOUR: ExpressionSpecification = ['interpolate', ['linear'], 0.22, 0, ['get', 'colour'], 1, '#ffffff'];
const NETWORK_BOUNDS: LngLatBoundsLike = [
    [113.91, 22.21],
    [114.30, 22.56],
];

interface MapCallbacks {
    onSelect(selection: { kind: 'train' | 'station'; id: string } | null): void;
    onError(message: string): void;
}

export interface MtrMap {
    getSelectionPoint(): { x: number; y: number } | null;
    setSnapshot(snapshot: TrainSnapshot, animate: boolean): void;
    setLanguage(language: 'en' | 'zh'): void;
    selectTrain(id: string | null): void;
    setPowerSave(enabled: boolean): void;
    destroy(): void;
}

const routeCasing: LineLayerSpecification = {
    id: 'mtr-route-casing',
    type: 'line',
    source: MTR_SOURCE_ID,
    'source-layer': 'mtr_routes',
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
        'line-color': '#ffffff',
        'line-width': ['interpolate', ['exponential', Math.SQRT2], ['zoom'], 0, 12 / 128, 22, 192],
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
        'line-width': ['interpolate', ['exponential', Math.SQRT2], ['zoom'], 0, 10 / 128, 22, 160],
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
            14, ['case', ['get', 'interchange'], 7.5, 5.5],
        ],
        'circle-stroke-color': '#111827',
        'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 9, 1.5, 14, 2.5],
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
        'text-offset': [0, 1.1],
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

function addMtrLayers(map: MapLibreMap, dark: boolean) {
    map.addSource(MTR_SOURCE_ID, {
        type: 'vector',
        url: `${MARTIN_URL}/mtr-routes,mtr-stations`,
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

function interpolateBearing(from: number, to: number, progress: number): number {
    const delta = ((to - from + 540) % 360) - 180;
    return (from + delta * progress + 360) % 360;
}

function createTrainRenderer(map: MapLibreMap) {
    let previous = new Map<string, TrainPosition>();
    let targets = new Map<string, TrainPosition>();
    let transitionStartedAt = performance.now();
    let lastRenderAt = 0;
    let animationFrame = 0;
    let duration = TRAIN_TRANSITION_MS;
    let powerSave = false;
    let dirty = true;
    let renderedZoom = NaN;
    let liveClock: { server: number; local: number } | null = null;

    const positionsAt = (now: number): TrainPosition[] => {
        const progress = duration === 0 ? 1 : Math.min(1, (now - transitionStartedAt) / duration);
        return [...targets.values()].map(target => {
            if (target.motion?.length && liveClock) return sampleMotion(target, liveClock.server + now - liveClock.local - 1000);
            const from = previous.get(target.id) || target;
            return {
                ...target,
                lng: from.lng + (target.lng - from.lng) * progress,
                lat: from.lat + (target.lat - from.lat) * progress,
                bearing: interpolateBearing(from.bearing, target.bearing, progress),
            };
        });
    };

    const render = (now: number) => {
        if (!document.hidden && now - lastRenderAt >= (powerSave ? 1000 : 1000 / 30)) {
            const source = map.getSource(TRAIN_SOURCE_ID) as GeoJSONSource | undefined;
            const zoom = map.getZoom();
            if (source && (dirty || zoom !== renderedZoom)) {
                source.setData(trainFeatures(positionsAt(now), zoom));
                renderedZoom = zoom;
                dirty = liveClock !== null || now < transitionStartedAt + duration;
            }
            lastRenderAt = now;
        }
        animationFrame = requestAnimationFrame(render);
    };
    animationFrame = requestAnimationFrame(render);

    return {
        setSnapshot(snapshot: TrainSnapshot, animate: boolean) {
            const now = performance.now();
            previous = new Map(positionsAt(now).map(train => [train.id, train]));
            const hasMotion = snapshot.trains.some(train => train.motion?.length);
            if (hasMotion && (!liveClock || !animate)) liveClock = { server: Date.parse(snapshot.timestamp), local: now };
            if (!hasMotion) liveClock = null;
            targets = new Map(snapshot.trains.map(train => {
                const old = targets.get(train.id);
                return [train.id, animate && train.motion?.length && old?.motion?.length
                    ? { ...train, motion: mergeMotion(old.motion, train.motion) } : train];
            }));
            transitionStartedAt = now;
            duration = animate ? TRAIN_TRANSITION_MS : 0;
            dirty = true;
        },
        getTrainCount: () => targets.size,
        getPosition: (id: string) => positionsAt(performance.now()).find(train => train.id === id),
        setPowerSave(enabled: boolean) { powerSave = enabled; },
        stop() {
            cancelAnimationFrame(animationFrame);
        },
    };
}

export function createMtrMap(container: HTMLElement, callbacks: MapCallbacks): MtrMap {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const styleName = prefersDark ? 'osm-liberty-dark' : 'positron';
    const map = new MapLibreMap({
        container,
        style: `${MARTIN_URL}/style/${styleName}`,
        center: [114.11, 22.36],
        zoom: 10.3,
        pitch: 42,
        bearing: -12,
        hash: true,
        maxPitch: 70,
    });
    const renderer = createTrainRenderer(map);
    let language: 'en' | 'zh' = 'zh';
    let selected: string | null = null;
    let stationAnchor: [number, number] | null = null;
    const updateLanguage = () => {
        if (map.getLayer('mtr-station-labels')) map.setLayoutProperty('mtr-station-labels', 'text-field', ['get', language === 'zh' ? 'name_zh' : 'name_en']);
    };
    const updateSelection = () => {
        if (map.getLayer('mtr-trains')) map.setPaintProperty('mtr-trains', 'fill-extrusion-color',
            ['case', ['==', ['get', 'id'], selected || ''], '#facc15', TRAIN_COLOUR]);
    };

    Object.assign(window, {
        __mtrDebug: {
            map,
            getTrainCount: renderer.getTrainCount,
        },
    });

    map.addControl(new NavigationControl({ visualizePitch: true }), 'top-right');
    map.addControl(new ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-right');

    map.on('load', () => {
        addMtrLayers(map, prefersDark);
        updateLanguage();
        updateSelection();
        if (!window.location.hash) {
            map.fitBounds(NETWORK_BOUNDS, { padding: 36, pitch: 42, bearing: -12, duration: 0 });
        }

        for (const layer of ['mtr-stations', 'mtr-trains']) {
            map.on('mouseenter', layer, () => map.getCanvas().style.cursor = 'pointer');
            map.on('mouseleave', layer, () => map.getCanvas().style.cursor = '');
        }
        map.on('click', event => {
            const features = map.queryRenderedFeatures(event.point, { layers: ['mtr-trains', 'mtr-stations'] });
            const train = features.find(feature => feature.layer.id === 'mtr-trains');
            const station = features.find(feature => feature.layer.id === 'mtr-stations');
            stationAnchor = station?.geometry.type === 'Point' ? station.geometry.coordinates.slice(0, 2) as [number, number] : null;
            callbacks.onSelect(train ? { kind: 'train', id: String(train.properties.id) }
                : station ? { kind: 'station', id: String(station.properties.code) } : null);
        });

    });

    map.on('error', (event: ErrorEvent) => { console.error('[MapLibre]', event.error); callbacks.onError(event.error.message); });

    return {
        getSelectionPoint() {
            const train = selected ? renderer.getPosition(selected) : undefined;
            const location = train ? [train.lng, train.lat] as [number, number] : stationAnchor;
            return location ? map.project(location) : null;
        },
        setSnapshot: renderer.setSnapshot,
        setPowerSave: renderer.setPowerSave,
        setLanguage(value) { language = value; updateLanguage(); },
        selectTrain(id) { selected = id; updateSelection(); },
        destroy() {
            renderer.stop();
            delete (window as Window & { __mtrDebug?: unknown }).__mtrDebug;
            map.remove();
        },
    };
}
