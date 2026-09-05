import { get } from './client.ts';
import type { NetworkCatalog, OperationsSnapshot, WeatherSnapshot, ServiceDaySnapshot, TrainSnapshot } from './types.ts';

export const getNetwork = (signal: AbortSignal) => get<NetworkCatalog>('network', signal);
export const getOperations = (signal: AbortSignal) => get<OperationsSnapshot>('operations', signal);
export const getWeather = (language: 'en' | 'zh', signal: AbortSignal) => get<WeatherSnapshot>(`weather?lang=${language}`, signal);
export const getServiceDay = (signal?: AbortSignal) => get<ServiceDaySnapshot>('service-day', signal);
export const getReplayServiceDay = (at: string, signal: AbortSignal) => get<ServiceDaySnapshot>(`service-day?at=${encodeURIComponent(at)}`, signal);
export const getReplayTrains = (at: string, signal: AbortSignal) => get<TrainSnapshot>(`trains?at=${encodeURIComponent(at)}`, signal);
