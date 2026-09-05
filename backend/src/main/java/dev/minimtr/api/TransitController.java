package dev.minimtr.api;

import dev.minimtr.model.vo.NetworkVo;
import dev.minimtr.model.vo.ServiceDayVo;
import dev.minimtr.service.NetworkService;
import dev.minimtr.service.ServiceDayService;
import dev.minimtr.service.TrainService;
import dev.minimtr.service.LiveTrainService;
import dev.minimtr.service.RealtimeService;
import dev.minimtr.model.vo.OperationsVo;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransitController {
    private final NetworkService network;
    private final ServiceDayService serviceDays;
    private final TrainService trains;
    private final Clock clock;
    private final LiveTrainService live;
    private final RealtimeService realtime;

    public TransitController(NetworkService network, ServiceDayService serviceDays, TrainService trains, Clock clock,
            LiveTrainService live, RealtimeService realtime) {
        this.network = network;
        this.serviceDays = serviceDays;
        this.trains = trains;
        this.clock = clock;
        this.live = live;
        this.realtime = realtime;
    }

    @GetMapping("/api/network")
    public ResponseEntity<NetworkVo> network() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(network.load());
    }

    @GetMapping("/api/service-day")
    public ResponseEntity<ServiceDayVo> serviceDay(@RequestParam(required = false) Instant at) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(serviceDays.load(at == null ? clock.instant() : at));
    }

    @GetMapping("/api/trains")
    public ResponseEntity<?> trains(@RequestParam(required = false) Instant at) {
        var snapshot = at == null ? live.latest() : trains.load(at);
        if (snapshot == null) return unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
    }

    @GetMapping(value = "/api/trains/live", produces = "text/event-stream")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> live() {
        var emitter = live.subscribe();
        if (emitter == null) return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(emitter);
    }

    @GetMapping("/api/operations")
    public ResponseEntity<OperationsVo> operations() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(realtime.operations());
    }

    private ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Live snapshot is not ready"));
    }
}
