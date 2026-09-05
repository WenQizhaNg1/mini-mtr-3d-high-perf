package dev.minimtr.service;

import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransitInputsTest {
    @Test void serviceTimesRespectTimezoneMidnightAndDst() {
        assertEquals(Instant.parse("2026-09-05T16:03:00Z"),ServiceTime.parse(LocalDate.parse("2026-09-05"),"24:03",ZoneId.of("Asia/Hong_Kong")));
        assertThrows(RuntimeException.class,() -> ServiceTime.parse(LocalDate.parse("2026-03-08"),"02:30",ZoneId.of("America/New_York")));
        assertThrows(RuntimeException.class,() -> ServiceTime.parse(LocalDate.parse("2026-11-01"),"01:30",ZoneId.of("America/New_York")));
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),ServiceTime.parse(LocalDate.parse("2026-11-01"),"2026-11-01T01:30:00-04:00",ZoneId.of("America/New_York")));
    }
    @Test void csvMapsFieldsAndKeepsDatesSeparate() {
        var trips=new TimetableCsv().parse(new TimetableCsv.Input(
            "train,pattern,seq,arrival,departure,serviceDate\nT,p,1,24:03,,2026-09-05\nT,p,0,,23:58,2026-09-05\nT,p,0,,23:58,2026-09-06",
            Map.of("trip","train")));
        assertEquals(2,trips.size());
        assertEquals(0,trips.getFirst().stops().getFirst().seq());
        assertEquals("24:03",trips.getFirst().stops().getLast().arrival());
    }
}
