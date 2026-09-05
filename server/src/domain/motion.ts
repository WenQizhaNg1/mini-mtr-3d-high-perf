import type { Arrival, Monitor, TrainPosition, TrainSnapshot } from './types.js';

export type Coordinate = [number, number];
export interface RoutePath {
    id: string; lineId: string; colour: string; destination: string;
    coordinates: Coordinate[]; distances: number[];
}
export interface MotionNode { at: number; distance: number; station: string; anchor?: boolean; }
export interface PlannedRun { id: string; patternId: string; nodes: MotionNode[]; }
interface Observation { station: string; eta: number; sourceAt: number; }
export interface MotionState extends PlannedRun {
    plan: MotionNode[];
    observation?: Observation;
    conflict: boolean;
    retired: boolean;
}
export interface MotionCheckpoint {
    version: 1; savedAt: number; states: MotionState[]; sources: [string, number][];
}

const OBSERVATION_TTL = 180_000;
const MATCH_WINDOW = 120_000;
const AMBIGUITY_MARGIN = 20_000;

export function distance(a: Coordinate, b: Coordinate): number {
    const r = Math.PI / 180;
    return 6371000 * Math.hypot((b[0] - a[0]) * r * Math.cos((a[1] + b[1]) / 2 * r), (b[1] - a[1]) * r);
}

export function locate(path: RoutePath, d: number): { lng: number; lat: number; bearing: number } {
    let index = 1;
    while (index < path.distances.length - 1 && path.distances[index]! < d) index++;
    const a = path.coordinates[index - 1]!;
    const b = path.coordinates[index]!;
    const length = path.distances[index]! - path.distances[index - 1]!;
    const fraction = Math.max(0, Math.min(1, (d - path.distances[index - 1]!) / length));
    return {
        lng: a[0] + (b[0] - a[0]) * fraction,
        lat: a[1] + (b[1] - a[1]) * fraction,
        bearing: (Math.atan2((b[0] - a[0]) * Math.cos(a[1] * Math.PI / 180), b[1] - a[1]) * 180 / Math.PI + 360) % 360,
    };
}

export function progress(nodes: MotionNode[], at: number): number {
    if (at <= nodes[0]!.at) return nodes[0]!.distance;
    for (let i = 1; i < nodes.length; i++) {
        const a = nodes[i - 1]!, b = nodes[i]!;
        if (at <= b.at) return a.distance + (b.distance - a.distance) * (at - a.at) / (b.at - a.at);
    }
    return nodes.at(-1)!.distance;
}

function predictionTime(state: MotionState, at: number): number {
    const observation = state.observation;
    // Once the last fresh ETA has been reached, downstream motion is explicitly
    // a trip-bounded forecast. A single monitor cannot observe later stations.
    if (!observation || observation.eta <= observation.sourceAt + OBSERVATION_TTL) return at;
    return Math.min(at, observation.sourceAt + OBSERVATION_TTL);
}

// Rebuild only the future. Preserve dwell durations and scale running durations
// to meet the observed arrival; an impossible ETA leaves the old trajectory intact.
export function retime(state: MotionState, station: string, eta: number, now: number, maxSpeed: number): boolean {
    const current = progress(state.nodes, predictionTime(state, now));
    const anchorAt = Math.max(now, state.nodes[0]!.at);
    const shift = now - predictionTime(state, now);
    const future = state.nodes.map(node => ({ ...node, at: node.at + shift }))
        .filter(node => node.at > anchorAt && node.distance >= current);
    const targetIndex = future.findIndex(node => node.station === station && node.distance > current);
    if (targetIndex < 0 || eta <= anchorAt) return false;
    const anchor: MotionNode = { at: anchorAt, distance: current,
        anchor: !state.nodes.some(node => node.at === anchorAt && node.distance === current && !node.anchor),
        station: [...state.nodes].reverse().find(node => node.at <= predictionTime(state, now))?.station || state.nodes[0]!.station };
    let dwell = 0, running = 0;
    for (let i = 0; i <= targetIndex; i++) {
        const a = i === 0 ? anchor : future[i - 1]!, b = future[i]!;
        if (a.distance === b.distance) dwell += b.at - a.at;
        else running += b.at - a.at;
    }
    const factor = (eta - anchorAt - dwell) / running;
    if (!(factor > 0)) return false;
    const nodes = state.nodes.filter(node => node.at < anchorAt);
    // A stale trajectory resumes from the frozen position, not its old timeline.
    if (predictionTime(state, now) < now) {
        nodes.splice(0, nodes.length, ...state.nodes.filter(node => node.at < predictionTime(state, now)));
        nodes.push({ ...anchor, at: predictionTime(state, now) });
    }
    nodes.push(anchor);
    for (let i = 0; i < future.length; i++) {
        const oldA = i === 0 ? anchor : future[i - 1]!, oldB = future[i]!;
        const dt = (oldB.at - oldA.at) * (i <= targetIndex && oldB.distance > oldA.distance ? factor : 1);
        if (dt <= 0 || (oldB.distance - oldA.distance) / (dt / 1000) > maxSpeed) return false;
        nodes.push({ ...oldB, at: nodes.at(-1)!.at + dt });
    }
    state.nodes = nodes;
    return true;
}

export class MotionEngine {
    readonly states = new Map<string, MotionState>();
    private sources = new Map<string, number>();
    constructor(readonly paths: Map<string, RoutePath>, private maxSpeeds: Map<string, number>) {}

    sync(runs: PlannedRun[], now: number) {
        const ids = new Set(runs.map(run => run.id));
        for (const [id, state] of this.states) {
            if (!ids.has(id) && state.plan.at(-1)!.at < now - 2 * 3600_000) this.states.delete(id);
        }
        for (const run of runs) {
            if (!this.states.has(run.id)) this.states.set(run.id, { ...run, plan: run.nodes, conflict: false, retired: run.nodes.at(-1)!.at < now });
        }
    }

    observe(monitor: Monitor, direction: 'UP' | 'DOWN', arrivals: Arrival[], sourceAt: number, now: number): number {
        const key = `${monitor.lineId}:${direction}:${monitor.stationCode}`;
        if (sourceAt <= (this.sources.get(key) ?? 0) || sourceAt < now - OBSERVATION_TTL || sourceAt > now + 10_000) return 0;
        this.sources.set(key, sourceAt);
        const patterns = Object.values(monitor.patterns[direction]);
        const unused = new Set([...this.states.values()].filter(state => !state.retired && patterns.includes(state.patternId)));
        let count = 0, previousOrder = -Infinity;
        for (const arrival of arrivals) {
            if (!arrival.destination) continue;
            const candidates = [...unused].flatMap(state => {
                if (this.paths.get(state.patternId)!.destination !== arrival.destination) return [];
                const current = progress(state.nodes, predictionTime(state, now));
                const target = state.nodes.find(node => node.station === monitor.stationCode && node.distance > current);
                const order = state.plan.find(node => node.station === monitor.stationCode)?.at;
                if (!target || order === undefined || order <= previousOrder) return [];
                const predicted = (state.observation?.station === monitor.stationCode ? state.observation.eta : target.at)
                    + now - predictionTime(state, now);
                const delta = Math.abs(predicted - arrival.at.getTime());
                return delta <= MATCH_WINDOW ? [{ state, delta, order, tracked: Boolean(state.observation) }] : [];
            }).sort((a, b) => Number(b.tracked) - Number(a.tracked) || a.delta - b.delta);
            const best = candidates[0], second = candidates[1];
            if (!best || (second && best.tracked === second.tracked && Math.abs(second.delta - best.delta) < AMBIGUITY_MARGIN)) continue;
            unused.delete(best.state);
            previousOrder = best.order;
            const accepted = retime(best.state, monitor.stationCode, arrival.at.getTime(), now, this.maxSpeeds.get(monitor.lineId)!);
            best.state.conflict = !accepted;
            if (accepted) {
                best.state.observation = { station: monitor.stationCode, eta: arrival.at.getTime(), sourceAt };
                count++;
            }
        }
        return count;
    }

    snapshot(now: number): TrainSnapshot {
        const trains: TrainPosition[] = [];
        for (const state of this.states.values()) {
            if (state.retired || now < state.nodes[0]!.at) continue;
            const at = predictionTime(state, now);
            const d = progress(state.nodes, at);
            if (d >= state.nodes.at(-1)!.distance) { state.retired = true; continue; }
            const path = this.paths.get(state.patternId)!;
            const previous = [...state.nodes].reverse().find(node => node.at <= at && !node.anchor) || state.nodes[0]!;
            const segmentStart = [...state.nodes].reverse().find(node => node.at <= at)!;
            const segmentEnd = state.nodes.find(node => node.at > at)!;
            const next = state.nodes.find(node => node.distance > d)!;
            const planned = state.plan.find(node => node.station === next.station)!;
            const validUntil = predictionTime(state, Math.min(now + 3000, state.nodes.at(-1)!.at));
            const times = new Set([now, Math.max(now, validUntil)]);
            for (const node of state.nodes) if (node.at > now && node.at < validUntil) times.add(node.at);
            // Include every crossed vertex so the client follows bends exactly.
            for (let i = 1; i < state.nodes.length; i++) {
                const a = state.nodes[i - 1]!, b = state.nodes[i]!;
                if (b.at < now || a.at > validUntil || a.distance === b.distance) continue;
                for (const vertex of path.distances) {
                    if (vertex <= a.distance || vertex >= b.distance) continue;
                    const t = a.at + (vertex - a.distance) / (b.distance - a.distance) * (b.at - a.at);
                    if (t > now && t < validUntil) times.add(t);
                }
            }
            trains.push({
                id: state.id, lineId: path.lineId, patternId: path.id, colour: path.colour,
                ...locate(path, d), state: segmentStart.distance === segmentEnd.distance ? 'dwell' : 'running',
                previousStation: previous.station, nextStation: next.station, destinationStation: path.destination,
                previousTime: new Date(previous.at).toISOString(), nextTime: new Date(next.at).toISOString(),
                delaySeconds: (next.at - planned.at) / 1000,
                estimate: predictionTime(state, now) < now ? 'stale' : state.conflict ? 'conflict'
                    : state.observation ? (now >= state.observation.eta ? 'predicted' : 'observed') : 'planned',
                motion: [...times].sort((a, b) => a - b).map(time => ({ at: time, ...locate(path, progress(state.nodes, predictionTime(state, time))) })),
            });
        }
        return { timestamp: new Date(now).toISOString(), trains };
    }

    checkpoint(now: number): MotionCheckpoint {
        return { version: 1, savedAt: now, states: [...this.states.values()], sources: [...this.sources] };
    }

    restore(checkpoint: MotionCheckpoint, now: number) {
        if (checkpoint.version !== 1 || checkpoint.savedAt > now || now - checkpoint.savedAt > 24 * 3600_000) return;
        for (const state of checkpoint.states) {
            const current = this.states.get(state.id);
            if (current && current.patternId === state.patternId && JSON.stringify(current.plan) === JSON.stringify(state.plan)) this.states.set(state.id, state);
        }
        this.sources = new Map(checkpoint.sources);
    }
}
