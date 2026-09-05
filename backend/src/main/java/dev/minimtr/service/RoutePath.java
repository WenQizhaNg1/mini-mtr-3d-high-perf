package dev.minimtr.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Immutable, process-local path. WGS84 planar fractions match ST_LineInterpolatePoint. */
public final class RoutePath {
    private final LengthIndexedLine path;
    private final double length;
    private final Coordinate[] coordinates;
    private final double[] units;
    private final double[] metres;
    private final String fingerprint;

    public RoutePath(byte[] wkb) {
        try {
            var geometry = new WKBReader().read(wkb);
            if (!(geometry instanceof LineString line) || line.isEmpty() || line.getLength() <= 0) {
                throw new IllegalArgumentException("Route geometry must be a non-zero LineString");
            }
            path = new LengthIndexedLine(line);
            length = line.getLength();
            var points = new java.util.ArrayList<Coordinate>();
            for (var point : line.getCoordinates()) {
                if (points.isEmpty() || !points.getLast().equals2D(point)) points.add(point);
            }
            coordinates = points.toArray(Coordinate[]::new);
            units = new double[coordinates.length];
            metres = new double[coordinates.length];
            for (int i = 1; i < coordinates.length; i++) {
                var a = coordinates[i - 1];
                var b = coordinates[i];
                units[i] = units[i - 1] + a.distance(b);
                metres[i] = metres[i - 1] + 6371000 * Math.hypot(
                        Math.toRadians(b.x - a.x) * Math.cos(Math.toRadians((a.y + b.y) / 2)),
                        Math.toRadians(b.y - a.y));
            }
            fingerprint = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(wkb));
        } catch (ParseException error) {
            throw new IllegalArgumentException("Invalid route WKB", error);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public Position locate(double fraction) {
        var position = path.extractPoint(fraction * length);
        boolean backwards = fraction >= 0.9999;
        double directionFraction = backwards ? Math.max(0, fraction - 0.0001) : Math.min(1, fraction + 0.0001);
        var direction = path.extractPoint(directionFraction * length);
        double bearing = backwards ? bearing(direction, position) : bearing(position, direction);
        return new Position(position.x, position.y, bearing);
    }

    private static double bearing(Coordinate from, Coordinate to) {
        // Match the old EPSG:3857 display heading, not a geodesic bearing.
        double dx = Math.toRadians(to.x - from.x);
        double dy = mercatorY(to.y) - mercatorY(from.y);
        return (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
    }

    private static double mercatorY(double latitude) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latitude) / 2));
    }

    public record Position(double lng, double lat, double bearing) {}

    public String fingerprint() { return fingerprint; }
    public double lengthMeters() { return metres[metres.length - 1]; }

    public record Projection(double fraction, double distanceMeters, double offsetMeters) {}

    /** Return distinct nearby passes so loops and crossing paths do not silently pick the first occurrence. */
    public java.util.List<Projection> project(double lng, double lat) {
        var candidates = new java.util.ArrayList<Projection>();
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = 1; i < coordinates.length; i++) {
            var a = coordinates[i - 1]; var b = coordinates[i];
            double dx = (b.x - a.x) * cos, dy = b.y - a.y;
            double t = Math.clamp(((lng - a.x) * cos * dx + (lat - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
            double offset = 6371000 * Math.toRadians(Math.hypot((lng - a.x - t * (b.x - a.x)) * cos,
                    lat - a.y - t * (b.y - a.y)));
            double fraction = (units[i - 1] + t * (units[i] - units[i - 1])) / units[units.length - 1];
            candidates.add(new Projection(fraction, metres[i - 1] + t * (metres[i] - metres[i - 1]), offset));
        }
        double best = candidates.stream().mapToDouble(Projection::offsetMeters).min().orElseThrow();
        var result = new java.util.ArrayList<Projection>();
        candidates.stream().filter(p -> p.offsetMeters() <= best + 1).sorted(java.util.Comparator.comparingDouble(Projection::offsetMeters))
                .forEach(p -> {
                    if (result.stream().noneMatch(q -> Math.abs(q.distanceMeters() - p.distanceMeters()) < 10)) result.add(p);
                });
        return java.util.List.copyOf(result);
    }

    // Package-local read-only vertices, used to include bends in the short-term trajectory.
    double[] metricVertices() { return metres; }

    public double metricDistance(double fraction) {
        double target = units[units.length - 1] * fraction;
        int i = segment(units, target);
        return metres[i - 1] + (target - units[i - 1]) / (units[i] - units[i - 1]) * (metres[i] - metres[i - 1]);
    }

    public Position locateMetric(double distance) {
        int i = segment(metres, distance);
        var a = coordinates[i - 1];
        var b = coordinates[i];
        double fraction = Math.clamp((distance - metres[i - 1]) / (metres[i] - metres[i - 1]), 0, 1);
        double heading = (Math.toDegrees(Math.atan2((b.x - a.x) * Math.cos(Math.toRadians(a.y)), b.y - a.y)) + 360) % 360;
        return new Position(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction, heading);
    }

    private static int segment(double[] distances, double distance) {
        int low = 1, high = distances.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (distances[mid] < distance) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
