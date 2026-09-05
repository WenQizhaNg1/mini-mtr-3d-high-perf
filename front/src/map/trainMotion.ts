import type { TrainPosition } from '../api/types';

type Motion = NonNullable<TrainPosition['motion']>;

export function mergeMotion(previous: Motion, next: Motion): Motion {
    const start = next[0]!.at;
    return [...previous.filter(point => point.at >= start - 5000 && point.at < start), ...next];
}

export function sampleMotion(train: TrainPosition, at: number): TrainPosition {
    const points = train.motion!;
    const first = points[0]!, last = points.at(-1)!;
    if (at <= first.at) return { ...train, ...first };
    if (at >= last.at) return { ...train, ...last, estimate: at > last.at ? 'stale' : train.estimate };
    const index = points.findIndex(point => point.at > at);
    const a = points[index - 1]!, b = points[index]!;
    const f = (at - a.at) / (b.at - a.at);
    return { ...train, lng: a.lng + (b.lng - a.lng) * f, lat: a.lat + (b.lat - a.lat) * f,
        bearing: (a.bearing + (((b.bearing - a.bearing + 540) % 360) - 180) * f + 360) % 360 };
}
