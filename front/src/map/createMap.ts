import { role, roleSource } from './roles';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import {
    GeoJSONSource,
    Map as MapLibreMap,
    NavigationControl,
    ScaleControl,
    setWorkerUrl,
    type ErrorEvent,
} from 'maplibre-gl';
import type { TrainPosition, TrainSnapshot } from '../api/types';
import type { StyleSpecification } from 'maplibre-gl';
import { trainFeatures } from './trainGeometry';
import { mergeMotion, sampleMotion } from './trainMotion';

// MapLibre 6 ships a separate module worker; let Vite resolve and bundle it.
setWorkerUrl(workerUrl);

const TRAIN_TRANSITION_MS = 1000;

export type MapSelection = { kind: 'train' | 'station'; id: string } | null;

interface MapCallbacks {
    onSelect(selection: MapSelection): void;
    onError(message: string): void;
    onPitch?(pitch: number): void;
}

export interface MtrMap {
    fitBounds(bounds: number[]): void;
    setStyle(style: StyleSpecification): void;
    getSelectionPoint(): { x: number; y: number } | null;
    followSelectedTrain(): void;
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
            const sourceId = roleSource(map.getStyle(), 'vehicles');
            const source = sourceId ? map.getSource(sourceId) as GeoJSONSource | undefined : undefined;
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
            const server = Date.parse(snapshot.timestamp);
            if (!hasMotion || !animate) liveClock = null;
            else if (!liveClock || Math.abs(server - liveClock.server - now + liveClock.local) > 3000)
                liveClock = {server, local:now};
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
        refresh() { dirty = true; },
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
        center: [114.165, 22.285],
        zoom: 14,
        pitch: 42,
        bearing: -12,
        hash: true,
        maxPitch: 70,
    });
    const renderer = createTrainRenderer(map);
    const updatePitch = () => callbacks.onPitch?.(map.getPitch());
    map.on('pitch', updatePitch);
    updatePitch();
    let language: 'en' | 'zh' = 'zh';
    let styleReady = false;
    let selected: string | null = null;
    let stationAnchor: [number, number] | null = null;
    const updateLanguage = () => {
        if (styleReady) map.setGlobalStateProperty('language', language);
    };
    const updateSelection = () => {
        const source = roleSource(map.getStyle(), 'vehicles');
        if (styleReady && selected && source && map.getSource(source))
            map.setFeatureState({ source, id: selected }, { selected: true });
    };

    Object.assign(window, {
        __mtrDebug: {
            map,
            getTrainCount: renderer.getTrainCount,
        },
    });

    map.addControl(new NavigationControl({ visualizePitch: true }), 'top-right');
    map.addControl(new ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-right');

    let interactiveLayers: string[] = [];
    const pointerEnter = () => { map.getCanvas().style.cursor = 'pointer'; };
    const pointerLeave = () => { map.getCanvas().style.cursor = ''; };
    map.on('style.load', () => {
        styleReady = true;
        updateLanguage();
        updateSelection();
        renderer.refresh();

        if (interactiveLayers.length) {
            map.off('mouseenter', interactiveLayers, pointerEnter);
            map.off('mouseleave', interactiveLayers, pointerLeave);
        }
        interactiveLayers = map.getStyle().layers.filter(layer => ['vehicles','stations'].includes(role(layer))).map(layer => layer.id);
        if (interactiveLayers.length) {
            map.on('mouseenter', interactiveLayers, pointerEnter);
            map.on('mouseleave', interactiveLayers, pointerLeave);
        }
    });
    map.on('click', event => {
        if (!styleReady) return;
        const features = interactiveLayers.length ? map.queryRenderedFeatures(event.point, { layers: interactiveLayers }) : [];
        const train = features.find(feature => role(feature.layer) === 'vehicles');
        const station = features.find(feature => role(feature.layer) === 'stations');
        stationAnchor = station?.geometry.type === 'Point' ? station.geometry.coordinates.slice(0, 2) as [number, number] : null;
        callbacks.onSelect(train ? { kind: 'train', id: String(train.properties.id) }
            : station ? { kind: 'station', id: String(station.properties.code) } : null);
    });

    map.on('error', (event: ErrorEvent) => { console.error('[MapLibre]', event.error); callbacks.onError(event.error.message); });

    return {
        fitBounds(bounds) { map.fitBounds([[bounds[0],bounds[1]],[bounds[2],bounds[3]]], {padding:90,maxZoom:16}); },
        setStyle(value) {
            styleReady = false;
            pointerLeave();
            map.setStyle(value, { diff: false });
        },
        getSelectionPoint() {
            const train = selected ? renderer.getPosition(selected) : undefined;
            const location = train ? [train.lng, train.lat] as [number, number] : stationAnchor;
            return location ? map.project(location) : null;
        },
        followSelectedTrain() {
            // Let the initial pan and user zoom/rotation finish before tracking resumes.
            if (!selected || map.isMoving()) return;
            const train = renderer.getPosition(selected);
            if (!train) return;
            const center = map.getCenter();
            if (center.lng !== train.lng || center.lat !== train.lat)
                map.jumpTo({ center: [train.lng, train.lat] });
        },
        setSnapshot: renderer.setSnapshot,
        setPowerSave: renderer.setPowerSave,
        setLanguage(value) { language = value; updateLanguage(); },
        selectTrain(id) {
            if (selected === id) return;
            if (selected) map.stop();
            const source = roleSource(map.getStyle(), 'vehicles');
            if (styleReady && selected && source && map.getSource(source))
                map.removeFeatureState({ source, id: selected }, 'selected');
            selected = id;
            updateSelection();
            const train = id ? renderer.getPosition(id) : undefined;
            if (train) map.easeTo({ center: [train.lng, train.lat], duration: 450 });
        },
        destroy() {
            renderer.stop();
            delete (window as Window & { __mtrDebug?: unknown }).__mtrDebug;
            map.remove();
        },
    };
}
