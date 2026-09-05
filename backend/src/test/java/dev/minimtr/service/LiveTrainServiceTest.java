package dev.minimtr.service;

import dev.minimtr.model.vo.TrainSnapshotVo;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveTrainServiceTest {
    @Test
    void slowSubscriberKeepsOnlyNewestSnapshotAndCloseWakesIt() throws Exception {
        var service = new LiveTrainService(mock(MotionService.class), mock(RealtimeService.class), false, false);
        var first = new TrainSnapshotVo(Instant.EPOCH, List.of());
        var last = new TrainSnapshotVo(Instant.EPOCH.plusSeconds(10), List.of());
        var subscription = service.new Subscription(first);
        for (int i = 0; i < 100; i++) subscription.publish(last);
        assertSame(last, subscription.next());
        subscription.close();
        assertNull(subscription.next());
        assertNull(service.subscribe());
    }
}
