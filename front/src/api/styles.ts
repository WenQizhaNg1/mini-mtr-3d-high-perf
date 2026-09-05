import type { LayerSpecification, StyleSpecification, SourceSpecification } from 'maplibre-gl';
import { get, request } from './client';

export interface MapStyle {
    code: string;
    name: string;
    basemap: string;
    layers: LayerSpecification[];
}

export const getMapStyle = (code: string, signal?: AbortSignal) =>
    get<MapStyle>(`styles/${encodeURIComponent(code)}`, signal);

export interface SourceEntry {
    name: string;
    source: SourceSpecification;
    bounds?: [number, number, number, number];
    layers: { id: string; geometry: string; fields: Record<string, string> }[];
}
export type SourceCatalog = Record<string, SourceEntry>;
export const getFullStyle = (code: string, signal?: AbortSignal) => get<StyleSpecification>(`styles/${encodeURIComponent(code)}/style.json`, signal);
export const listStyles = (signal?: AbortSignal) => request<Omit<MapStyle, 'layers'>[]>('styles', { signal });
export const getSources = (signal?: AbortSignal) => request<SourceCatalog>('map-sources', { signal });
export const getBasemap = (code: string, signal?: AbortSignal) => request<StyleSpecification>(`basemaps/${code}/style.json`, { signal });
export const saveStyle = (style: MapStyle, token: string, signal?: AbortSignal) =>
    request<void>(`styles/${encodeURIComponent(style.code)}`, { method: 'PUT', body: style, token, signal });
