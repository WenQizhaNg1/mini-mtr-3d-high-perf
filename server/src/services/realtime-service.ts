import { extractIncident, normalizeArrivals } from '../domain/realtime.js';
import { parseHkTimestamp } from '../domain/time.js';
import type { MotionService } from './motion-service.js';
import type {
    LineIncident,
    Monitor,
    OperationsSnapshot,
    RealtimeStatus,
    ServiceConfig,
} from '../domain/types.js';
import type { MtrClient } from '../integrations/mtr-client.js';

const POLL_INTERVAL_MS = 30_000;
const REQUEST_GAP_MS = 220;

export interface RealtimeService {
    readonly status: RealtimeStatus;
    readonly operations: OperationsSnapshot;
    start(): void;
    stop(): Promise<void>;
}

function sleep(milliseconds: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
}

export function createRealtimeService(
    motion: Pick<MotionService, 'observe' | 'save'>,
    service: ServiceConfig,
    mtrClient: MtrClient,
    logger: Pick<Console, 'warn'> = console,
): RealtimeService {
    const monitors: Monitor[] = service.lines.map(line => ({
        lineId: line.lineId,
        apiCode: line.apiCode,
        stationCode: line.monitorStation,
        patterns: line.patterns,
    }));
    const status: RealtimeStatus = {
        enabled: true,
        healthy: null,
        lastPollAt: null,
        lastError: null,
        updatedTrains: 0,
    };
    let running = false;
    let inFlight: Promise<void> | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let request: AbortController | null = null;
    const incidents = new Map<string, LineIncident>();

    const updateIncident = (
        monitor: Monitor,
        response: Parameters<typeof extractIncident>[0],
        observedAt: Date,
    ) => {
        const incident = extractIncident(response, observedAt);
        if (incident) {
            incidents.set(monitor.lineId, incident);
        } else if (response.status === 1 && response.isdelay !== 'Y') {
            incidents.delete(monitor.lineId);
        }
    };

    const pollMonitor = async (monitor: Monitor): Promise<number> => {
        const controller = new AbortController();
        request = controller;
        const timeout = setTimeout(() => controller.abort(), 10_000);
        try {
            const response = await mtrClient.getSchedule(
                monitor.apiCode,
                monitor.stationCode,
                controller.signal,
            );
            const observedAt = new Date();
            updateIncident(monitor, response, observedAt);
            const station = response.data?.[`${monitor.apiCode}-${monitor.stationCode}`];
            const sourceAt = parseHkTimestamp(station?.curr_time || response.curr_time || '');
            if (response.status !== 1) return 0;
            if (!sourceAt) throw new Error(`${monitor.apiCode}-${monitor.stationCode}: missing or invalid ETA source time`);
            let matched = 0;

            for (const direction of ['UP', 'DOWN'] as const) {
                const arrivals = normalizeArrivals(response, monitor, direction);
                matched += motion.observe(monitor, direction, arrivals, sourceAt.getTime(), observedAt.getTime());
            }

            await motion.save();
            return matched;
        } finally {
            clearTimeout(timeout);
            if (request === controller) request = null;
        }
    };

    const poll = async () => {
        let updatedTrains = 0;
        let firstError: unknown = null;

        for (const monitor of monitors) {
            if (!running) break;
            try {
                updatedTrains += await pollMonitor(monitor);
            } catch (error) {
                firstError ||= error;
                logger.warn(
                    `[train-api] realtime poll failed for ${monitor.apiCode}-${monitor.stationCode}`,
                    error,
                );
            }
            if (running) await sleep(REQUEST_GAP_MS);
        }

        status.healthy = firstError === null;
        status.lastPollAt = new Date().toISOString();
        status.lastError = firstError instanceof Error ? firstError.message : firstError ? String(firstError) : null;
        status.updatedTrains = updatedTrains;
        if (running) timer = setTimeout(() => { inFlight = poll(); }, POLL_INTERVAL_MS);
    };

    return {
        status,
        get operations() {
            return {
                realtime: { ...status },
                lines: service.lines.map(line => ({
                    lineId: line.lineId,
                    incident: incidents.get(line.lineId) || null,
                })),
            };
        },
        start() {
            if (running) return;
            running = true;
            inFlight = poll();
        },
        async stop() {
            running = false;
            if (timer) clearTimeout(timer);
            timer = null;
            request?.abort();
            await inFlight;
        },
    };
}
