export const API_URL = (import.meta.env?.VITE_TRAIN_API_URL || '').replace(/\/$/, '');

export async function request<T>(path: string, options: { method?: string; body?: unknown; token?: string; signal?: AbortSignal } = {}): Promise<T> {
    const response = await fetch(`/api/${path}`, {
        method: options.method || 'GET',
        headers: { ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
            ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(60000)]) : AbortSignal.timeout(60000),
    });
    if (!response.ok) {
        const text = await response.text();
        let message = text;
        try { message = JSON.parse(text).error || text; } catch { /* Preserve non-JSON proxy errors. */ }
        throw new Error(`HTTP ${response.status}: ${message}`);
    }
    return response.status === 204 ? undefined as T : response.json();
}

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const timeout = AbortSignal.timeout(15000);
    const response = await fetch(`${API_URL}/api/${path}`, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status} ${await response.text()}`);
    return response.json();
}
