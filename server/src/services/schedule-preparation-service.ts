import { nextServiceDate, serviceDateAt } from '../domain/time.js';

export function createSchedulePreparationService(
    ensure: (serviceDate: string) => Promise<boolean>,
    serviceDayStart: string,
    logger: Pick<Console, 'info' | 'error'> = console,
    now: () => Date = () => new Date(),
) {
    let running = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let pending: Promise<void> | undefined;

    async function prepare() {
        const current = serviceDateAt(now(), serviceDayStart);
        for (const date of [current, nextServiceDate(current)]) {
            if (!running) return;
            if (await ensure(date)) logger.info(`[schedule] prepared ${date}`);
        }
    }

    function scheduleNext() {
        if (!running) return;
        // Schedule after completion so slow generation cannot overlap the next check.
        timer = setTimeout(() => {
            pending = prepare()
                .catch(error => logger.error('[schedule] preparation failed; retrying next minute', error))
                .finally(scheduleNext);
        }, 60_000);
    }

    return {
        async start() {
            if (running) return pending;
            running = true;
            pending = prepare();
            try {
                await pending;
                scheduleNext();
            } catch (error) {
                running = false;
                throw error;
            }
        },
        async stop() {
            running = false;
            clearTimeout(timer);
            await pending;
        },
    };
}
