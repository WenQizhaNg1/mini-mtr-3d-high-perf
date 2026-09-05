export const API_URL = (import.meta.env?.VITE_TRAIN_API_URL || 'http://127.0.0.1:3002').replace(/\/$/, '');

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const timeout = AbortSignal.timeout(15000);
    const response = await fetch(`${API_URL}/api/${path}`, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status} ${await response.text()}`);
    return response.json();
}
