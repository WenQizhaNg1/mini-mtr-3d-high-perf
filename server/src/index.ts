import { serve } from '@hono/node-server';
import { createApp } from './app.js';
import { loadConfig } from './config.js';
import { createDatabase } from './db/client.js';
import { checkDatabase } from './db/train-queries.js';
import { createMtrClient } from './integrations/mtr-client.js';
import { createWeatherClient } from './integrations/weather-client.js';
import { createLiveTrainService } from './services/live-train-service.js';
import { createMotionService } from './services/motion-service.js';
import { createNetworkService } from './services/network-service.js';
import { createRealtimeService } from './services/realtime-service.js';
import { createServiceDayService } from './services/service-day-service.js';
import { createTrainService } from './services/train-service.js';
import { createWeatherService } from './services/weather-service.js';
import { ensureSchedule } from './services/schedule-service.js';
import { createSchedulePreparationService } from './services/schedule-preparation-service.js';

const config = loadConfig();
const database = createDatabase(config.connectionString);
const schedules = createSchedulePreparationService(
    date => ensureSchedule(database.pool, config.service, date), config.service.serviceDayStart,
);
try {
    await schedules.start();
} catch (error) {
    await database.close();
    throw new Error('Cannot start train API: schedule preparation failed', { cause: error });
}
const trains = createTrainService(database.db);
const motion = await createMotionService(database.pool, config.service);
const liveTrains = createLiveTrainService(motion);
const network = createNetworkService(database.db, config.service);
const serviceDays = createServiceDayService(database.db, config.service);
const weather = createWeatherService(createWeatherClient());
const realtime = config.realtimeEnabled
    ? createRealtimeService(motion, config.service, createMtrClient())
    : null;
const app = createApp({
    checkDatabase: () => checkDatabase(database.db),
    trains,
    liveTrains,
    network,
    serviceDays,
    weather,
    getRealtimeStatus: () => realtime?.status || { enabled: false },
    getOperations: () => realtime?.operations || {
        realtime: { enabled: false },
        lines: config.service.lines.map(line => ({ lineId: line.lineId, incident: null })),
    },
});

const server = serve({
    fetch: app.fetch,
    hostname: config.host,
    port: config.port,
}, info => {
    console.log(`[train-api] listening on http://${info.address}:${info.port}`);
    liveTrains.start();
    realtime?.start();
});

let shutdownPromise: Promise<void> | null = null;
function shutdown(): Promise<void> {
    if (shutdownPromise) return shutdownPromise;
    shutdownPromise = (async () => {
        await realtime?.stop();
        await liveTrains.stop();
        await schedules.stop();
        await new Promise<void>((resolve, reject) => {
            server.close(error => error ? reject(error) : resolve());
        });
        await motion.close();
        await database.close();
    })();
    return shutdownPromise;
}

function handleSignal() {
    void shutdown().catch(error => {
        console.error('[train-api] shutdown failed', error);
        process.exitCode = 1;
    });
}

process.once('SIGINT', handleSignal);
process.once('SIGTERM', handleSignal);
