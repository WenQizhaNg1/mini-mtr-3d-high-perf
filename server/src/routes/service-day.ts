import { Hono } from 'hono';
import type { ServiceDayService } from '../services/service-day-service.js';

export function createServiceDayRoutes(serviceDays: ServiceDayService): Hono {
    const routes = new Hono();
    routes.get('/', async context => {
        const value = context.req.query('at');
        const at = value ? new Date(value) : new Date();
        if (!Number.isFinite(at.getTime())) {
            return context.json({ error: 'Invalid at timestamp' }, 400);
        }
        context.header('Cache-Control', 'no-store');
        return context.json(await serviceDays.load(at));
    });
    return routes;
}
