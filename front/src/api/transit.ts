import { get } from './client.ts';
import type { NetworkCatalog, WeatherSnapshot, ServiceDaySnapshot, TrainSnapshot } from './types.ts';

export const getNetwork = (signal: AbortSignal, operator = 'mtr') => get<NetworkCatalog>(`network?operator=${encodeURIComponent(operator)}`, signal);
export const getWeather = (language: 'en' | 'zh', signal: AbortSignal) => get<WeatherSnapshot>(`weather?lang=${language}`, signal);
export const getServiceDay = (signal?: AbortSignal, operator = 'mtr') => get<ServiceDaySnapshot>(`service-day?operator=${encodeURIComponent(operator)}`, signal);
export const getReplayServiceDay = (at: string, signal: AbortSignal, operator = 'mtr') => get<ServiceDaySnapshot>(`service-day?operator=${encodeURIComponent(operator)}&at=${encodeURIComponent(at)}`, signal);
export const getReplayTrains = (at: string, signal: AbortSignal, operator = 'mtr') => get<TrainSnapshot>(`trains?operator=${encodeURIComponent(operator)}&mode=simulation&at=${encodeURIComponent(at)}`, signal);
