import { Hono } from 'hono';
import { cors } from 'hono/cors';
import type { OperationsSnapshot, RealtimeStatus } from './domain/types.js';
import { createHealthRoutes } from './routes/health.js';
import { createNetworkRoutes } from './routes/network.js';
import { createOperationsRoutes } from './routes/operations.js';
import { createServiceDayRoutes } from './routes/service-day.js';
import { createTrainRoutes } from './routes/trains.js';
import { createWeatherRoutes } from './routes/weather.js';
import type { LiveTrainService } from './services/live-train-service.js';
import type { NetworkService } from './services/network-service.js';
import type { ServiceDayService } from './services/service-day-service.js';
import type { TrainService } from './services/train-service.js';
import type { WeatherService } from './services/weather-service.js';

export interface AppDependencies {
    checkDatabase(): Promise<void>;
    trains: TrainService;
    liveTrains: LiveTrainService;
    network: NetworkService;
    serviceDays: ServiceDayService;
    weather: WeatherService;
    getRealtimeStatus(): RealtimeStatus | { enabled: false };
    getOperations(): OperationsSnapshot;
    logger?: Pick<Console, 'error'>;
}

export function createApp(dependencies: AppDependencies): Hono {
    const logger = dependencies.logger || console;
    const app = new Hono();
    app.use('*', cors());
    app.route('/health', createHealthRoutes(
        dependencies.checkDatabase,
        dependencies.getRealtimeStatus,
    ));
    app.route('/api/trains', createTrainRoutes(
        dependencies.trains,
        dependencies.liveTrains,
        logger,
    ));
    app.route('/api/network', createNetworkRoutes(dependencies.network));
    app.route('/api/service-day', createServiceDayRoutes(dependencies.serviceDays));
    app.route('/api/operations', createOperationsRoutes(dependencies.getOperations));
    app.route('/api/weather', createWeatherRoutes(dependencies.weather));

    app.notFound(context => context.json({ error: 'Not found' }, 404));
    app.onError((error, context) => {
        logger.error('[train-api] request failed', error);
        return context.json({ error: 'Internal server error' }, 500);
    });
    return app;
}
