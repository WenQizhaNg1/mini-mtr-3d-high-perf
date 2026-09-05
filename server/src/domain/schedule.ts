import type { ServiceConfig } from './types.js';
import { isServiceDate } from './time.js';

export interface ScheduleStop {
    patternId: string;
    stopSequence: number;
    stationCode: string;
    distanceM: number;
}

export interface GeneratedRun {
    id: string;
    serviceDate: string;
    patternId: string;
    startsAt: Date;
    endsAt: Date;
}

export interface GeneratedLeg {
    trainId: string;
    sequence: number;
    fromStopSequence: number;
    toStopSequence: number;
    departureAt: Date;
    arrivalAt: Date;
}

export interface GeneratedSchedule {
    runs: GeneratedRun[];
    legs: GeneratedLeg[];
}

function startMinuteOfDay(serviceDayStart: string): number {
    const match = /^(\d{2}):(\d{2}):\d{2}\+08:00$/.exec(serviceDayStart);
    if (!match) throw new Error(`Invalid service day start: ${serviceDayStart}`);
    return Number(match[1]) * 60 + Number(match[2]);
}

export function choosePattern(
    patterns: { default: string; alternate?: string; every?: number },
    sequence: number,
): string {
    if (patterns.alternate && patterns.every && sequence % patterns.every === 0) {
        return patterns.alternate;
    }
    return patterns.default;
}

function headwayMinutes(
    startMinute: number,
    offsetMinutes: number,
    headways: { peak: number; normal: number; evening: number; late: number },
): number {
    const minuteOfDay = (startMinute + offsetMinutes) % (24 * 60);
    const inRange = (fromHour: number, toHour: number): boolean => {
        const from = fromHour * 60;
        const to = toHour * 60;
        return from < to
            ? minuteOfDay >= from && minuteOfDay < to
            : minuteOfDay >= from || minuteOfDay < to;
    };
    if (inRange(7, 9.5) || inRange(17.5, 19.5)) return headways.peak;
    if (inRange(19.5, 21.5)) return headways.evening;
    if (inRange(21.5, 1.5)) return headways.late;
    return headways.normal;
}

function groupStops(stops: ScheduleStop[]): Map<string, ScheduleStop[]> {
    const result = new Map<string, ScheduleStop[]>();
    for (const stop of stops) {
        const patternStops = result.get(stop.patternId) ?? [];
        patternStops.push(stop);
        result.set(stop.patternId, patternStops);
    }
    for (const patternStops of result.values()) {
        patternStops.sort((a, b) => a.stopSequence - b.stopSequence);
    }
    return result;
}

export function generateSchedule(
    config: ServiceConfig,
    serviceDate: string,
    routeStops: ScheduleStop[],
): GeneratedSchedule {
    if (!isServiceDate(serviceDate)) {
        throw new Error(`Invalid service date: ${serviceDate}`);
    }
    const serviceDayStart = new Date(`${serviceDate}T${config.serviceDayStart}`);
    if (!Number.isFinite(serviceDayStart.getTime())) {
        throw new Error(`Invalid service day start: ${config.serviceDayStart}`);
    }

    const stopsByPattern = groupStops(routeStops);
    const runs: GeneratedRun[] = [];
    const legs: GeneratedLeg[] = [];
    const startMinute = startMinuteOfDay(config.serviceDayStart);
    const serviceEnd = serviceDayStart.getTime() + config.serviceEndOffsetMinutes * 60_000;

    for (const line of config.lines) {
        for (const direction of ['UP', 'DOWN'] as const) {
            let offsetMinutes = line.firstTrainOffsetMinutes + (direction === 'DOWN' ? 1.5 : 0);
            let sequence = 0;

            while (offsetMinutes <= line.lastTrainOffsetMinutes) {
                sequence++;
                const patternId = choosePattern(line.patterns[direction], sequence);
                const stops = stopsByPattern.get(patternId);
                if (!stops || stops.length < 2) {
                    throw new Error(`Missing route stops for ${patternId}`);
                }

                const trainId = `${serviceDate}:${line.lineId}:${direction}:${String(sequence).padStart(3, '0')}`;
                const trainLegs: GeneratedLeg[] = [];
                let currentTime = serviceDayStart.getTime() + offsetMinutes * 60_000;
                for (let stopIndex = 0; stopIndex < stops.length - 1; stopIndex++) {
                    const from = stops[stopIndex]!;
                    const to = stops[stopIndex + 1]!;
                    const distance = to.distanceM - from.distanceM;
                    if (distance <= 0) {
                        throw new Error(`Non-increasing route distance for ${patternId} at stop ${to.stopSequence}`);
                    }
                    const travelMs = distance / (line.speedKmph * 1000 / 3600)
                        * config.travelTimeFactor * 1000;
                    const arrivalTime = currentTime + travelMs;
                    trainLegs.push({
                        trainId,
                        sequence: stopIndex,
                        fromStopSequence: from.stopSequence,
                        toStopSequence: to.stopSequence,
                        departureAt: new Date(currentTime),
                        arrivalAt: new Date(arrivalTime),
                    });
                    currentTime = arrivalTime + config.dwellSeconds * 1000;
                }

                const firstLeg = trainLegs[0]!;
                const finalLeg = trainLegs.at(-1)!;
                if (finalLeg.arrivalAt.getTime() <= serviceEnd) {
                    runs.push({
                        id: trainId,
                        serviceDate,
                        patternId,
                        startsAt: firstLeg.departureAt,
                        endsAt: finalLeg.arrivalAt,
                    });
                    legs.push(...trainLegs);
                }

                const headway = headwayMinutes(startMinute, offsetMinutes, line.headways);
                if (headway <= 0) throw new Error(`Invalid headway for ${line.lineId}`);
                offsetMinutes += headway;
            }
        }
    }

    return { runs, legs };
}
