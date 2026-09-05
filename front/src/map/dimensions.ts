// Vehicle widths in pixels at zero pitch, independent of database route paint.
// Preserve the overview size and cap close-ups without shrinking on zoom-in.
const TRAIN_WIDTHS = [[0, 10 / 128 * 1.725], [11, 10 / Math.sqrt(8) * 1.725], [14, 24.15], [18, 26]] as const;
// Fixed visual ordering, not track elevation. Separate coplanar train roofs
// by 0.5% of their height per line, independent of the current snapshot.
export const TRAIN_HEIGHT_OFFSETS: Readonly<Record<string, number>> = {
    ISL: 0, TWL: 0.005, KTL: 0.010, TKL: 0.015, EAL: 0.020,
    TML: 0.025, TCL: 0.030, AEL: 0.035, DRL: 0.040, SIL: 0.045,
};
const BASE = Math.SQRT2;

export function trainWidthAtZoom(zoom: number): number {
    if (zoom <= TRAIN_WIDTHS[0][0]) return TRAIN_WIDTHS[0][1];
    for (let i = 1; i < TRAIN_WIDTHS.length; i++) {
        const [end, width] = TRAIN_WIDTHS[i];
        const [start, previous] = TRAIN_WIDTHS[i - 1];
        if (zoom <= end) {
            const progress = (BASE ** (zoom - start) - 1) / (BASE ** (end - start) - 1);
            return previous + (width - previous) * progress;
        }
    }
    return TRAIN_WIDTHS[TRAIN_WIDTHS.length - 1][1];
}
