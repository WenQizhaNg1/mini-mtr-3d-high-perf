import { Hono } from 'hono';
import type { RealtimeStatus } from '../domain/types.js';

export function createHealthRoutes(
    checkDatabase: () => Promise<void>,
    getRealtimeStatus: () => RealtimeStatus | { enabled: false },
): Hono {
    const routes = new Hono();
    routes.get('/', async context => {
        await checkDatabase();
        return context.json({
            status: 'ok',
            realtime: getRealtimeStatus(),
        });
    });
    return routes;
}
