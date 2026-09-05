import type { Database } from '../db/client.js';
import { loadServiceDayDatabaseState } from '../db/service-day-queries.js';
import { nextServiceDate, serviceWindowAt } from '../domain/time.js';
import type { ServiceConfig, ServiceDaySnapshot } from '../domain/types.js';

export interface ServiceDayService {
    load(at: Date): Promise<ServiceDaySnapshot>;
}

function isoTimestamp(value: Date | string | null): string | null {
    return value ? (value instanceof Date ? value : new Date(value)).toISOString() : null;
}

export function createServiceDayService(db: Database, config: ServiceConfig): ServiceDayService {
    return {
        async load(at) {
            const window = serviceWindowAt(
                at,
                config.serviceDayStart,
                config.serviceEndOffsetMinutes,
            );
            const nextDate = nextServiceDate(window.serviceDate);
            const state = await loadServiceDayDatabaseState(db, window.serviceDate, nextDate);
            return {
                serviceDate: window.serviceDate,
                active: window.active,
                startsAt: window.startsAt.toISOString(),
                endsAt: window.endsAt.toISOString(),
                replay: {
                    startsAt: isoTimestamp(state.replay_starts_at),
                    endsAt: isoTimestamp(state.replay_ends_at),
                },
                schedules: {
                    current: state.current_exists,
                    nextServiceDate: nextDate,
                    next: state.next_exists,
                },
            };
        },
    };
}
