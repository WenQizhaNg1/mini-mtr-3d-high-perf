import { Hono } from 'hono';
import type { OperationsSnapshot } from '../domain/types.js';

export function createOperationsRoutes(getOperations: () => OperationsSnapshot): Hono {
    const routes = new Hono();
    routes.get('/', context => {
        context.header('Cache-Control', 'no-store');
        return context.json(getOperations());
    });
    return routes;
}
