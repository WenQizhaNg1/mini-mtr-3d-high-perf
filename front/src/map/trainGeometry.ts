import type { FeatureCollection, Polygon } from 'geojson';
import type { TrainPosition } from '../api/types';
import { routeWidthAtZoom, TRAIN_WIDTH_RATIO } from './dimensions.ts';

const EARTH_CIRCUMFERENCE = 40075016.68557849;
type TrainProperties = Omit<TrainPosition, 'lng' | 'lat' | 'motion'> & { height: number };

// Match the line layer's fractional-zoom scaling before converting to Mercator.
export function trainFeatures(positions: TrainPosition[], zoom: number): FeatureCollection<Polygon, TrainProperties> {
    const pixel = 1 / (512 * 2 ** zoom);
    const width = routeWidthAtZoom(zoom) * TRAIN_WIDTH_RATIO;
    const halfWidth = width / 2;
    const halfLength = width * 1.3;
    return {
        type: 'FeatureCollection',
        features: positions.map(({ lng, lat, motion: _motion, ...properties }) => {
            const latitude = lat * Math.PI / 180;
            const x = (lng + 180) / 360;
            const y = (1 - Math.log(Math.tan(Math.PI / 4 + latitude / 2)) / Math.PI) / 2;
            const bearing = properties.bearing * Math.PI / 180;
            const ring = [[-halfWidth, -halfLength], [halfWidth, -halfLength], [halfWidth, halfLength], [-halfWidth, halfLength]].map(([right, forward]) => {
                const dx = (right * Math.cos(bearing) + forward * Math.sin(bearing)) * pixel;
                const dy = (right * Math.sin(bearing) - forward * Math.cos(bearing)) * pixel;
                return [(x + dx) * 360 - 180, Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + dy)))) * 180 / Math.PI];
            });
            ring.push([...ring[0]]);
            return {
                type: 'Feature',
                id: properties.id,
                geometry: { type: 'Polygon', coordinates: [ring] },
                properties: { ...properties, height: width * 0.45 * pixel * EARTH_CIRCUMFERENCE * Math.cos(latitude) },
            };
        }),
    };
}
