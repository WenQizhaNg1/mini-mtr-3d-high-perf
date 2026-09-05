import type { LayerSpecification, StyleSpecification } from 'maplibre-gl';

export function role(layer: LayerSpecification): string {
    return (layer.metadata as { role?: string } | undefined)?.role || '';
}
export function roleSource(style: StyleSpecification | undefined, value: string): string | undefined {
    const layer = style?.layers.find(layer => role(layer) === value && 'source' in layer);
    return layer && 'source' in layer && typeof layer.source === 'string' ? layer.source : undefined;
}
