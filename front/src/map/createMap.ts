import { TRAIN_SOURCE_ID } from './layers';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import {
    GeoJSONSource,
    Map as MapLibreMap,
    NavigationControl,
    ScaleControl,
    setWorkerUrl,
    type ErrorEvent,
} from 'maplibre-gl';
import type { LngLatBoundsLike } from 'maplibre-gl';
import type { TrainPosition, TrainSnapshot } from '../api/types';
import type { StyleSpecification } from 'maplibre-gl';
import { trainFeatures } from './trainGeometry';
import { mergeMotion, sampleMotion } from './trainMotion';

// MapLibre 6 ships a separate module worker; let Vite resolve and bundle it.
setWorkerUrl(workerUrl);

const TRAIN_TRANSITION_MS = 1000;
const NETWORK_BOUNDS: LngLatBoundsLike = [
    [113.91, 22.21],
    [114.30, 22.56],
];

export type MapSelection = { kind: 'train' | 'station'; id: string } | null;

interface MapCallbacks {
    onSelect(selection: MapSelection): void;
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

export function createMtrMap(container: HTMLElement, callbacks: MapCallbacks, style: StyleSpecification): MtrMap {
    const map = new MapLibreMap({
        container,
        style,
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
        if (map.getSource(TRAIN_SOURCE_ID)) map.setGlobalStateProperty('language', language);
    };
    const updateSelection = () => {
        if (selected && map.getSource(TRAIN_SOURCE_ID))
            map.setFeatureState({ source: TRAIN_SOURCE_ID, id: selected }, { selected: true });
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
        updateLanguage();
        updateSelection();
        if (!window.location.hash) {
            map.fitBounds(NETWORK_BOUNDS, { padding: 36, pitch: 42, bearing: -12, duration: 0 });
        }

        for (const layer of ['mtr-stations', 'mtr-trains']) {
            if (!map.getLayer(layer)) continue;
            map.on('mouseenter', layer, () => map.getCanvas().style.cursor = 'pointer');
            map.on('mouseleave', layer, () => map.getCanvas().style.cursor = '');
        }
        map.on('click', event => {
            const layers = ['mtr-trains', 'mtr-stations'].filter(id => map.getLayer(id));
            const features = layers.length ? map.queryRenderedFeatures(event.point, { layers }) : [];
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
        selectTrain(id) {
            if (selected && map.getSource(TRAIN_SOURCE_ID))
                map.removeFeatureState({ source: TRAIN_SOURCE_ID, id: selected }, 'selected');
            selected = id;
            updateSelection();
        },
        destroy() {
            renderer.stop();
            delete (window as Window & { __mtrDebug?: unknown }).__mtrDebug;
            map.remove();
        },
    };
}
