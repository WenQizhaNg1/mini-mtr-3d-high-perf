import type { LayerSpecification, StyleSpecification } from 'maplibre-gl';
import type { MapStyle, SourceCatalog } from '../api/styles';

export function composeStyle(base: StyleSpecification, draft: MapStyle, catalog: SourceCatalog): StyleSpecification {
    const result: StyleSpecification = JSON.parse(JSON.stringify(base));
    result.name = draft.name;
    result.state = { language: { default: 'zh' } };
    const routes: LayerSpecification[] = [];
    const overlays: LayerSpecification[] = [];
    for (const layer of draft.layers) {
        const key = 'source' in layer && typeof layer.source === 'string' ? layer.source : '';
        if (!catalog[key]) throw new Error(`数据源未发布或不可用：${key}`);
        result.sources[key] = catalog[key].source;
        if (layer.type === 'line' && key === 'mtr' && layer['source-layer'] === 'mtr_routes') routes.push(layer);
        else overlays.push(layer);
    }
    let building = result.layers.findIndex(layer => layer.type === 'fill-extrusion');
    if (building < 0) building = result.layers.length;
    result.layers.splice(building, 0, ...routes);
    result.layers.push(...overlays);
    return result;
}

export function defaultLayer(id: string, source: string, sourceLayer: string, type: string): LayerSpecification {
    const paint: Record<string, unknown> = type === 'line' ? { 'line-color': '#168b92', 'line-width': 5 }
        : type === 'circle' ? { 'circle-color': '#168b92', 'circle-radius': 7, 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 }
        : type === 'fill-extrusion' ? { 'fill-extrusion-color': '#168b92', 'fill-extrusion-height': 12, 'fill-extrusion-opacity': 0.9 }
        : type === 'fill' ? { 'fill-color': '#168b92', 'fill-opacity': 0.6 }
        : { 'text-color': '#243746', 'text-halo-color': '#ffffff', 'text-halo-width': 1.5 };
    return { id, type, source, ...(sourceLayer ? { 'source-layer': sourceLayer } : {}), paint,
        layout: type === 'symbol' ? { 'text-field': ['get', 'name'], 'text-font': ['Noto Sans CJK SC Regular'], 'text-size': 14 } : {},
    } as LayerSpecification;
}
