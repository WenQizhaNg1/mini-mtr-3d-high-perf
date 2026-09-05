package dev.minimtr.service;

import dev.minimtr.TestSettings;
import dev.minimtr.model.dto.*;
import dev.minimtr.repo.MtrClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MtrAdapterTest {
    @Test void etaDoesNotNeedTimetableOrVehicleIdentity() throws Exception {
        var client=mock(MtrClient.class);
        when(client.schedule("ISL","QUB")).thenReturn(new MtrResponse(1,"successful",null,"N","2026-09-05 12:00:00",
            Map.of("ISL-QUB",new MtrResponse.Station("2026-09-05 12:00:00",
                List.of(new MtrResponse.Eta("CHW","2026-09-05 12:03:00","Y")),List.of()))));
        var config=new TransitConfig("港铁","Asia/Hong_Kong","mtr","realtime",null,null,
            new TransitConfig.Realtime(List.of(new TransitConfig.Monitor("ISL","QUB")),60));
        var values=new MtrAdapter(client,TestSettings.load()).readRealtime("mtr",config);
        assertEquals(1,values.size());
        var eta=values.getFirst();
        assertEquals("eta",eta.kind()); assertNull(eta.vehicleId()); assertNull(eta.lng()); assertNull(eta.distanceMeters());
        assertEquals(Instant.parse("2026-09-05T04:03:00Z"),eta.eta());
        verify(client).schedule("ISL","QUB");
    }
}
