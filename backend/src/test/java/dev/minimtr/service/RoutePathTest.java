package dev.minimtr.service;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.*;

class RoutePathTest {
    @Test
    void interpolatesPlanarFractionAndHandlesEndHeading() throws Exception {
        var geometry = new WKTReader().read("LINESTRING(114 22,114.01 22,114.01 22.02)");
        var path = new RoutePath(new WKBWriter().write(geometry));
        assertEquals(114.005, path.locate(1.0 / 6).lng(), 1e-10);
        assertEquals(90, path.locate(1.0 / 6).bearing(), 1e-6);
        assertEquals(22.005, path.locate(0.5).lat(), 1e-10);
        assertEquals(0, path.locate(0.5).bearing(), 1e-6);
        assertEquals(22.02, path.locate(1).lat(), 1e-10);
        assertEquals(0, path.locate(1).bearing(), 1e-6);
    }

    @Test
    void rejectsNonRouteGeometry() throws Exception {
        var point = new WKBWriter().write(new WKTReader().read("POINT(114 22)"));
        assertThrows(IllegalArgumentException.class, () -> new RoutePath(point));
    }
}
