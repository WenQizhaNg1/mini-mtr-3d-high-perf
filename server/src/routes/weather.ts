import { Hono } from 'hono';
import type { WeatherLanguage } from '../domain/types.js';
import type { WeatherService } from '../services/weather-service.js';

export function createWeatherRoutes(weather: WeatherService): Hono {
    const routes = new Hono();
    routes.get('/', async context => {
        const value = context.req.query('lang') || 'zh';
        if (value !== 'en' && value !== 'zh') {
            return context.json({ error: 'Invalid lang; expected en or zh' }, 400);
        }
        context.header('Cache-Control', 'no-store');
        return context.json(await weather.load(value as WeatherLanguage));
    });
    return routes;
}
