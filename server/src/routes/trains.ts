import { Hono } from 'hono';
import { streamSSE } from 'hono/streaming';
import type { LiveTrainService } from '../services/live-train-service.js';
import type { TrainService } from '../services/train-service.js';

export function createTrainRoutes(
    trains: TrainService,
    liveTrains: LiveTrainService,
    logger: Pick<Console, 'error'>,
): Hono {
    const routes = new Hono();

    routes.get('/', async context => {
        const value = context.req.query('at');
        if (!value && liveTrains.latestSnapshot) {
            context.header('Cache-Control', 'no-store');
            return context.json(liveTrains.latestSnapshot);
        }
        if (!value) return context.json({ error: 'Live snapshot is not ready' }, 503);

        const at = value ? new Date(value) : new Date();
        if (!Number.isFinite(at.getTime())) {
            return context.json({ error: 'Invalid at timestamp' }, 400);
        }

        context.header('Cache-Control', 'no-store');
        return context.json(await trains.loadSnapshot(at));
    });

    routes.get('/live', context => streamSSE(context, async stream => {
        const subscription = liveTrains.subscribe();
        stream.onAbort(() => subscription.close());

        try {
            await stream.write('retry: 3000\n\n');
            while (!stream.aborted) {
                const snapshot = await subscription.next();
                if (!snapshot || stream.aborted) break;
                await stream.writeSSE({ data: JSON.stringify(snapshot) });
            }
        } finally {
            subscription.close();
        }
    }, async error => {
        logger.error('[train-api] live stream failed', error);
    }));

    return routes;
}
