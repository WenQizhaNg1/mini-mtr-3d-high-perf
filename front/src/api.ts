// Wire contracts only; business rules remain in server/.
export type { TrainPosition, TrainSnapshot, NetworkCatalog, ServiceDaySnapshot,
    OperationsSnapshot, WeatherSnapshot } from '../../server/src/domain/types.ts';

export const API_URL = (import.meta.env?.VITE_TRAIN_API_URL || 'http://127.0.0.1:3001').replace(/\/$/, '');

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const timeout = AbortSignal.timeout(15000);
    const response = await fetch(`${API_URL}/api/${path}`, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status} ${await response.text()}`);
    return response.json();
}
