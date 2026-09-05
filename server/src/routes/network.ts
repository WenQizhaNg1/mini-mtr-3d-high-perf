import { Hono } from 'hono';
import type { NetworkService } from '../services/network-service.js';

export function createNetworkRoutes(network: NetworkService): Hono {
    const routes = new Hono();
    routes.get('/', async context => {
        context.header('Cache-Control', 'no-store');
        return context.json(await network.loadCatalog());
    });
    return routes;
}
