export function localTime(at: number, timezone: string): string {
    const parts = new Intl.DateTimeFormat('en-GB', {timeZone: timezone, year:'numeric',month:'2-digit',day:'2-digit',
        hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(at);
    const p = Object.fromEntries(parts.map(part => [part.type,part.value]));
    return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:${p.second}`;
}
export function parseLocal(value: string, timezone: string): number {
    const normalized = value.length === 16 ? value + ':00' : value;
    const utc = Date.parse(normalized + 'Z');
    if (!Number.isFinite(utc)) throw new Error('日期无效');
    const offsets = new Set([-36,0,36].map(hours => {
        const at = utc + hours * 3600000;
        return Date.parse(localTime(at, timezone) + 'Z') - at;
    }));
    const candidates = [...offsets].map(offset => utc - offset).filter(at => localTime(at, timezone) === normalized);
    if (candidates.length !== 1) throw new Error('该本地时间不存在或因夏令时重复，请使用时间轴选择');
    return candidates[0];
}
