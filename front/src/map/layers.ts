import type { Map as MapLibreMap, LayerSpecification } from 'maplibre-gl';

export const MTR_SOURCE_ID = 'mtr';
export const TRAIN_SOURCE_ID = 'mtr-trains';

export function addMtrLayers(map: MapLibreMap, layers: LayerSpecification[], martinUrl: string) {
    for (const layer of layers) {
        if (!('source' in layer) || ![MTR_SOURCE_ID, TRAIN_SOURCE_ID].includes(layer.source as string))
            throw new Error(`Unregistered source for layer: ${layer.id}`);
        if (map.getLayer(layer.id)) throw new Error(`Business layer conflicts with basemap: ${layer.id}`);
    }
    map.addSource(MTR_SOURCE_ID, {
        type: 'vector',
        url: `${martinUrl}/mtr-routes,mtr-stations`,
    });
    map.addSource(TRAIN_SOURCE_ID, {
        type: 'geojson',
        promoteId: 'id',
        data: { type: 'FeatureCollection', features: [] },
    });
    const buildingLayer = map.getStyle().layers.find(layer => layer.type === 'fill-extrusion')?.id;
    for (const layer of layers) {
        // Route strokes stay beneath buildings; other business layers overlay the basemap.
        const isRoute = layer.type === 'line' && layer.source === MTR_SOURCE_ID && layer['source-layer'] === 'mtr_routes';
        map.addLayer(layer, isRoute ? buildingLayer : undefined);
    }
}
