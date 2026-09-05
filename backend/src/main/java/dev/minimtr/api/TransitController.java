package dev.minimtr.api;

import dev.minimtr.service.*;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class TransitController {
    private final TransitFrames frames;
    private final TransitConfigService configs;
    private final MotionStreamService streams;
    public TransitController(TransitFrames frames,TransitConfigService configs,MotionStreamService streams) {
        this.frames=frames; this.configs=configs; this.streams=streams;
    }
    private ResponseEntity<?> response(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
    @GetMapping("/api/operators")
    public ResponseEntity<?> operators() {
        return response(configs.list().stream().map(c -> java.util.Map.of("code",c.code(),"name",c.config().name(),
                "timezone",c.config().timezone(),"mode",c.config().mode(),"modes",configs.adapter(c.config().adapter()).modes())).toList());
    }
    @GetMapping("/api/network")
    public ResponseEntity<?> network(@RequestParam(defaultValue="mtr") String operator) { return response(frames.network(operator)); }
    @GetMapping("/api/service-day")
    public ResponseEntity<?> serviceDay(@RequestParam(defaultValue="mtr") String operator,@RequestParam(required=false) Instant at) {
        return response(frames.serviceDay(operator,at));
    }
    @GetMapping("/api/trains")
    public ResponseEntity<?> trains(@RequestParam(defaultValue="mtr") String operator,@RequestParam(required=false) String mode,
            @RequestParam(required=false) Instant at) { return response(frames.frame(operator,mode,at)); }
    @GetMapping(value="/api/trains/live",produces="text/event-stream")
    public ResponseEntity<SseEmitter> live(@RequestParam(defaultValue="mtr") String operator,@RequestParam(required=false) String mode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no").body(streams.subscribe(operator,mode));
    }
    @GetMapping(value="/api/transit/events",produces="text/event-stream")
    public ResponseEntity<SseEmitter> changes(@RequestParam String operator) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no").body(streams.changes(operator));
    }
}
