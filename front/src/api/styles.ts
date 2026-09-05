import type { LayerSpecification } from 'maplibre-gl';
import { get } from './client';

export interface MapStyle {
    code: string;
    name: string;
    basemap: string;
    layers: LayerSpecification[];
}

export const getMapStyle = (code: string, signal: AbortSignal) =>
    get<MapStyle>(`styles/${encodeURIComponent(code)}`, signal);
