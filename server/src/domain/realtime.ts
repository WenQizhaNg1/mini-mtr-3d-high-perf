import { parseHkTimestamp } from './time.js';
import type {
    Arrival,
    LineIncident,
    MtrScheduleResponse,
    Monitor,
    RealtimeMatch,
    TrainCandidate,
} from './types.js';

const MAX_DELAY_MS = 10 * 60_000;
const SERVICE_ENDED = /(服務.{0,4}(已)?結束|service has? ended|has? completed (its )?(service|journey))/i;
const SUCCESS = /(successful|正常|no special)/i;

export function extractIncident(
    response: MtrScheduleResponse,
    observedAt: Date,
): LineIncident | null {
    const message = (response.message || '').trim();
    const isDelay = response.isdelay === 'Y';

    if (response.status === 0 && message && !SERVICE_ENDED.test(message)) {
        return {
            message,
            url: response.url || null,
            isDelay,
            updatedAt: observedAt.toISOString(),
        };
    }
    if (isDelay) {
        return {
            message: message && !SUCCESS.test(message)
                ? message
                : 'Train service is delayed. Please follow station announcements.',
            url: response.url || null,
            isDelay: true,
            updatedAt: observedAt.toISOString(),
        };
    }
    return null;
}

export function normalizeArrivals(
    response: MtrScheduleResponse,
    monitor: Monitor,
    direction: 'UP' | 'DOWN',
): Arrival[] {
    const schedule = response.data?.[`${monitor.apiCode}-${monitor.stationCode}`];
    const rows = schedule?.[direction];
    if (!Array.isArray(rows)) return [];

    return rows.flatMap(row => {
        const at = parseHkTimestamp(row?.time);
        if (!at || row?.valid === 'N') return [];
        return [{ at, destination: row.dest || '' }];
    }).sort((left, right) => left.at.getTime() - right.at.getTime());
}

export function matchArrivals(candidates: TrainCandidate[], arrivals: Arrival[]): RealtimeMatch[] {
    const unused = new Set(candidates.map((_, index) => index));
    const matches: RealtimeMatch[] = [];

    for (const arrival of arrivals) {
        const eligible = [...unused].filter(index => (
            !arrival.destination || candidates[index]?.destination === arrival.destination
        ));
        const first = eligible[0];
        if (first === undefined) continue;

        const index = eligible.reduce((best, current) => {
            const bestCandidate = candidates[best];
            const currentCandidate = candidates[current];
            if (!bestCandidate || !currentCandidate) return best;
            const bestDelta = Math.abs(arrival.at.getTime() - bestCandidate.scheduledAt.getTime());
            const currentDelta = Math.abs(arrival.at.getTime() - currentCandidate.scheduledAt.getTime());
            return currentDelta < bestDelta ? current : best;
        }, first);
        const candidate = candidates[index];
        if (!candidate) continue;
        const delayMilliseconds = arrival.at.getTime() - candidate.scheduledAt.getTime();
        if (Math.abs(delayMilliseconds) > MAX_DELAY_MS) continue;

        unused.delete(index);
        matches.push({
            trainId: candidate.id,
            delaySeconds: delayMilliseconds / 1000,
        });
    }

    return matches;
}
