// Train geometry baseline, in pixels at zero pitch. Layer paint now lives in app.layer;
// changing its line-width does not change vehicle geometry.
const ROUTE_WIDTHS = [[0, 10 / 128], [11, 10 / Math.sqrt(8)], [14, 14], [18, 64], [22, 256]] as const;
export const ROUTE_CASING_RATIO = 1.15;
export const TRAIN_WIDTH_RATIO = ROUTE_CASING_RATIO * 1.5;
const BASE = Math.SQRT2;

export function routeWidthAtZoom(zoom: number): number {
    if (zoom <= ROUTE_WIDTHS[0][0]) return ROUTE_WIDTHS[0][1];
    for (let i = 1; i < ROUTE_WIDTHS.length; i++) {
        const [end, width] = ROUTE_WIDTHS[i];
        const [start, previous] = ROUTE_WIDTHS[i - 1];
        if (zoom <= end) {
            const progress = (BASE ** (zoom - start) - 1) / (BASE ** (end - start) - 1);
            return previous + (width - previous) * progress;
        }
    }
    return ROUTE_WIDTHS[ROUTE_WIDTHS.length - 1][1];
}
