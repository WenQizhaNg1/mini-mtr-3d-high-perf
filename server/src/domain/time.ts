const HK_TIMESTAMP = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})$/;
const HK_DATE = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Hong_Kong',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
});

export function parseHkTimestamp(value: unknown): Date | null {
    if (typeof value !== 'string') return null;
    const match = value.match(HK_TIMESTAMP);
    if (!match) return null;
    const timestamp = new Date(`${match[1]}T${match[2]}+08:00`);
    return Number.isFinite(timestamp.getTime()) ? timestamp : null;
}

function startMinuteOfDay(serviceDayStart: string): number {
    const match = /^(\d{2}):(\d{2}):\d{2}\+08:00$/.exec(serviceDayStart);
    if (!match) throw new Error(`Invalid service day start: ${serviceDayStart}`);
    return Number(match[1]) * 60 + Number(match[2]);
}

export function isServiceDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(`${value}T00:00:00+08:00`);
    return Number.isFinite(date.getTime()) && HK_DATE.format(date) === value;
}

export function serviceDateAt(at: Date, serviceDayStart: string): string {
    const shifted = new Date(at.getTime() - startMinuteOfDay(serviceDayStart) * 60_000);
    return HK_DATE.format(shifted);
}

export function nextServiceDate(serviceDate: string): string {
    if (!isServiceDate(serviceDate)) throw new Error(`Invalid service date: ${serviceDate}`);
    const noon = new Date(`${serviceDate}T12:00:00+08:00`);
    return HK_DATE.format(new Date(noon.getTime() + 24 * 60 * 60_000));
}

export interface ServiceWindow {
    serviceDate: string;
    startsAt: Date;
    endsAt: Date;
    active: boolean;
}

export function serviceWindowAt(
    at: Date,
    serviceDayStart: string,
    serviceEndOffsetMinutes: number,
): ServiceWindow {
    const serviceDate = serviceDateAt(at, serviceDayStart);
    const startsAt = new Date(`${serviceDate}T${serviceDayStart}`);
    const endsAt = new Date(startsAt.getTime() + serviceEndOffsetMinutes * 60_000);
    return {
        serviceDate,
        startsAt,
        endsAt,
        active: at >= startsAt && at < endsAt,
    };
}
