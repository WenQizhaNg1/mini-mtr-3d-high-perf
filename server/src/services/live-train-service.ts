import type { TrainSnapshot } from '../domain/types.js';
import type { TrainService } from './train-service.js';

export interface LiveSubscription {
    next(): Promise<TrainSnapshot | null>;
    close(): void;
}

export interface LiveTrainService {
    readonly latestSnapshot: TrainSnapshot | null;
    start(): void;
    stop(): Promise<void>;
    subscribe(): LiveSubscription;
}

interface Subscriber {
    closed: boolean;
    snapshot: TrainSnapshot | null;
    wake: (() => void) | null;
}

export function createLiveTrainService(
    trains: TrainService,
    logger: Pick<Console, 'error'> = console,
): LiveTrainService {
    const subscribers = new Set<Subscriber>();
    let latestSnapshot: TrainSnapshot | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let running = false;
    let inFlight: Promise<void> | null = null;

    const publish = (snapshot: TrainSnapshot) => {
        for (const subscriber of subscribers) {
            subscriber.snapshot = snapshot;
            subscriber.wake?.();
            subscriber.wake = null;
        }
    };

    const update = async () => {
        try {
            latestSnapshot = await trains.loadSnapshot(new Date());
            publish(latestSnapshot);
        } catch (error) {
            logger.error('[train-api] live snapshot failed', error);
        } finally {
            if (running) timer = setTimeout(() => { inFlight = update(); }, 1000);
        }
    };

    return {
        get latestSnapshot() {
            return latestSnapshot;
        },
        start() {
            if (running) return;
            running = true;
            inFlight = update();
        },
        async stop() {
            if (!running) return;
            running = false;
            if (timer) clearTimeout(timer);
            timer = null;
            for (const subscriber of subscribers) {
                subscriber.closed = true;
                subscriber.wake?.();
            }
            subscribers.clear();
            await inFlight;
        },
        subscribe() {
            const subscriber: Subscriber = {
                closed: false,
                snapshot: latestSnapshot,
                wake: null,
            };
            subscribers.add(subscriber);
            return {
                async next() {
                    while (!subscriber.closed && !subscriber.snapshot) {
                        await new Promise<void>(resolve => subscriber.wake = resolve);
                        subscriber.wake = null;
                    }
                    if (subscriber.closed) return null;
                    const snapshot = subscriber.snapshot;
                    subscriber.snapshot = null;
                    return snapshot;
                },
                close() {
                    if (subscriber.closed) return;
                    subscriber.closed = true;
                    subscriber.wake?.();
                    subscribers.delete(subscriber);
                },
            };
        },
    };
}
