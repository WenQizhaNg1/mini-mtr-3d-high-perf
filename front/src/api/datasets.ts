import type { FeatureCollection } from 'geojson';
import { request } from './client';

export interface Dataset { id: number; code: string; name: string; ontology_code: string; fields: Record<string, string>; published: boolean }
export interface Ontology { code: string; name: string; geometry_types: string[]; enabled: boolean; is_dynamic: boolean; description: string }
export interface ImportInput {
    code: string; name: string; ontology: string; format: 'geojson' | 'csv' | 'wkt'; content: string;
    geometryColumn?: string; longitudeColumn?: string; latitudeColumn?: string; mapping: Record<string, string>;
}
export interface Inspection { fields: string[]; samples: unknown[] }
export interface ImportPreview { count: number; fields: Record<string, string>; geojson: FeatureCollection }
export const listDatasets = (token: string, signal?: AbortSignal) => request<Dataset[]>('admin/datasets', { token, signal });
export const listOntology = (signal?: AbortSignal) => request<Ontology[]>('ontology', { signal });
export const inspectImport = (body: ImportInput, token: string, signal?: AbortSignal) => request<Inspection>('datasets/inspect', { method: 'POST', body, token, signal });
export const previewImport = (body: ImportInput, token: string, signal?: AbortSignal) => request<ImportPreview>('datasets/preview', { method: 'POST', body, token, signal });
export const createDataset = (body: ImportInput, token: string, signal?: AbortSignal) => request<Dataset>('datasets', { method: 'POST', body, token, signal });
export const publishDataset = (code: string, published: boolean, token: string, signal?: AbortSignal) =>
    request<Dataset>(`datasets/${code}/publication`, { method: 'PUT', body: { published }, token, signal });
