package dev.minimtr.api;

import dev.minimtr.model.dto.*;
import dev.minimtr.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/transit")
public class TransitAdminController {
    private final TransitConfigService configs;
    private final RealtimeFrames realtime;
    private final TimetableCsv csv;
    public TransitAdminController(TransitConfigService configs,RealtimeFrames realtime,TimetableCsv csv) {
        this.configs=configs; this.realtime=realtime; this.csv=csv;
    }
    @GetMapping public Object list() { return configs.list(); }
    @GetMapping("/adapters") public Object adapters() { return configs.adapters(); }
    @GetMapping("/{code}") public Object get(@PathVariable String code) { return configs.get(code); }
    @DeleteMapping("/{code}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String code) { configs.remove(code); }
    @PutMapping("/{code}") public Object save(@PathVariable String code,@RequestBody TransitConfig config) { return configs.save(code,config); }
    @PostMapping("/timetable/preview") public Object timetable(@RequestBody TimetableCsv.Input input) { return csv.parse(input); }
    @PutMapping("/{code}/observations") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void observations(@PathVariable String code,@RequestBody List<RealtimeObservation> input) {
        realtime.ingest(code,input);
    }
}
