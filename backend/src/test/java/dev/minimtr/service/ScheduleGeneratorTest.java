package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.entity.RouteStop;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleGeneratorTest {
    private static final LocalDate DATE = LocalDate.parse("2026-09-05");
    private static final ServiceSettings.Headways HEADWAYS = new ServiceSettings.Headways(2, 5, 6, 9);

    private ServiceSettings settings(int endMinutes, double lastOffset) {
        return new ServiceSettings(1, "05:30:00+08:00", endMinutes, 30, 1,
                List.of(new ServiceSettings.Line("T", "T", "Test", "测试", "#123456", "A", "A", 60, 0,
                        lastOffset, HEADWAYS, Map.of(
                            "UP", new ServiceSettings.Pattern("forward", "branch", 2),
                            "DOWN", new ServiceSettings.Pattern("reverse", null, null)))));
    }

    private List<RouteStop> stops() {
        return List.of(new RouteStop(1, "forward", "T", 0, 0), new RouteStop(1, "forward", "T", 2, 1000),
                new RouteStop(1, "forward", "T", 5, 2000),
                new RouteStop(2, "reverse", "T", 0, 0), new RouteStop(2, "reverse", "T", 1, 2000),
                new RouteStop(3, "branch", "T", 0, 0), new RouteStop(3, "branch", "T", 1, 3000));
    }

    @Test
    void preservesTerminalNullsDwellAndStopSequence() {
        var settings = settings(60, 2);
        var trips = new ScheduleGenerator(settings).generate(DATE, stops());
        assertEquals(2, trips.size());
        var times = trips.getFirst().stops();
        var start = settings.startsAt(DATE);
        assertNull(times.getFirst().arrivalAt());
        assertEquals(start, times.getFirst().departureAt());
        assertEquals(2, times.get(1).seq());
        assertEquals(start.plusSeconds(60), times.get(1).arrivalAt());
        assertEquals(start.plusSeconds(90), times.get(1).departureAt());
        assertEquals(start.plusSeconds(150), times.getLast().arrivalAt());
        assertNull(times.getLast().departureAt());
        assertEquals(start.plusSeconds(90), trips.getLast().stops().getFirst().departureAt());
    }

    @Test
    void alternatesBranchAndRejectsTrainsBeyondServiceEnd() {
        var trips = new ScheduleGenerator(settings(60, 10)).generate(DATE, stops());
        assertEquals(3, trips.stream().filter(t -> t.code().contains(":UP:")).count());
        assertEquals(3, trips.stream().filter(t -> t.code().endsWith(":UP:002")).findFirst().orElseThrow().routeId());
        var shortTrips = new ScheduleGenerator(settings(3, 10)).generate(DATE, stops());
        assertEquals(1, shortTrips.size());
    }

    @Test
    void rejectsMissingAndNonIncreasingPaths() {
        var generator = new ScheduleGenerator(settings(60, 2));
        assertThrows(IllegalStateException.class, () -> generator.generate(DATE, List.of()));
        assertThrows(IllegalStateException.class, () -> generator.generate(DATE, List.of(
                new RouteStop(1, "forward", "T", 0, 100), new RouteStop(1, "forward", "T", 1, 100))));
    }

    @Test
    void followsPeakEveningAndOvernightHeadwayBoundaries() {
        assertEquals(5, ScheduleGenerator.headway(419, HEADWAYS));
        assertEquals(2, ScheduleGenerator.headway(420, HEADWAYS));
        assertEquals(5, ScheduleGenerator.headway(570, HEADWAYS));
        assertEquals(6, ScheduleGenerator.headway(1170, HEADWAYS));
        assertEquals(9, ScheduleGenerator.headway(1290, HEADWAYS));
        assertEquals(9, ScheduleGenerator.headway(1440 + 89, HEADWAYS));
        assertEquals(5, ScheduleGenerator.headway(1440 + 90, HEADWAYS));
        assertThrows(IllegalArgumentException.class, () -> new ServiceSettings.Headways(0, 5, 6, 9));
    }
}
