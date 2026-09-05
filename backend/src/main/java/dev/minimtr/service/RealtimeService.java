package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.dto.Arrival;
import dev.minimtr.model.dto.MtrResponse;
import dev.minimtr.model.vo.OperationsVo;
import dev.minimtr.model.vo.RealtimeStatusVo;
import dev.minimtr.repo.MtrClient;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RealtimeService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private static final DateTimeFormatter HK_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern ENDED = Pattern.compile("服務.{0,4}(已)?結束|service has? ended|has? completed (its )?(service|journey)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUCCESS = Pattern.compile("successful|正常|no special", Pattern.CASE_INSENSITIVE);
    private final MtrClient client;
    private final MotionService motion;
    private final ServiceSettings settings;
    private final Clock clock;
    private final boolean enabled;
    private final Map<String, OperationsVo.Incident> incidents = new ConcurrentHashMap<>();
    private volatile RealtimeStatusVo status;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public RealtimeService(MtrClient client, MotionService motion, ServiceSettings settings, Clock clock,
            @Value("${realtime.enabled:${REALTIME_ENABLED:true}}") boolean enabled) {
        this.client = client; this.motion = motion; this.settings = settings; this.clock = clock; this.enabled = enabled;
        status = new RealtimeStatusVo(enabled, null, null, null, 0);
    }

    public void start() {
        if (!enabled || running) return;
        for (var line : settings.lines()) if (line.monitorStation() == null || line.monitorStation().isBlank()) {
            throw new IllegalArgumentException("Missing monitor station for " + line.lineId());
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("mtr-poll").factory());
        executor.scheduleWithFixedDelay(this::poll, 0, 30, TimeUnit.SECONDS);
    }

    private void poll() {
        int updated = 0;
        String firstError = null;
        for (var line : settings.lines()) {
            if (!running) break;
            try {
                var response = client.schedule(line.apiCode(), line.monitorStation());
                updated += accept(line, response, clock.instant());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception error) {
                if (firstError == null) firstError = line.lineId() + ": " + error.getMessage();
                log.warn("Realtime poll failed for {}-{}", line.apiCode(), line.monitorStation(), error);
            }
            if (running) try { Thread.sleep(220); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
        status = new RealtimeStatusVo(enabled, firstError == null, clock.instant(), firstError, updated);
    }

    int accept(ServiceSettings.Line line, MtrResponse response, Instant observedAt) throws java.sql.SQLException {
        var incident = incident(response, observedAt);
        if (incident != null) incidents.put(line.lineId(), incident);
        else if (Objects.equals(response.status(), 1) && !"Y".equals(response.isdelay())) incidents.remove(line.lineId());
        if (!Objects.equals(response.status(), 1)) return 0;
        var station = response.data() == null ? null : response.data().get(line.apiCode() + "-" + line.monitorStation());
        // DRL can report success with '-' timestamps and no predictions. There is no observation to timestamp.
        if (station != null && (station.up() == null || station.up().isEmpty())
                && (station.down() == null || station.down().isEmpty())) return 0;
        var source = parseTime(station != null && station.currentTime() != null && !station.currentTime().isBlank()
                ? station.currentTime() : response.currentTime());
        if (source == null) throw new IllegalArgumentException("Missing or invalid ETA source time");
        int count = 0;
        for (String direction : List.of("UP", "DOWN")) {
            var rows = station == null ? null : direction.equals("UP") ? station.up() : station.down();
            count += motion.observe(line, direction, arrivals(rows), source.toEpochMilli(), observedAt.toEpochMilli());
        }
        motion.save();
        return count;
    }

    static Instant parseTime(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value, HK_TIME).toInstant(ZoneOffset.ofHours(8)); }
        catch (DateTimeParseException error) { return null; }
    }

    static List<Arrival> arrivals(List<MtrResponse.Eta> rows) {
        if (rows == null) return List.of();
        var result = new ArrayList<Arrival>();
        for (var row : rows) {
            if (row == null || "N".equals(row.valid())) continue;
            var time = parseTime(row.time());
            if (time != null) result.add(new Arrival(time.toEpochMilli(), row.dest()));
        }
        result.sort(Comparator.comparingDouble(Arrival::at));
        return List.copyOf(result);
    }

    static OperationsVo.Incident incident(MtrResponse response, Instant at) {
        String message = response.message() == null ? "" : response.message().trim();
        boolean delay = "Y".equals(response.isdelay());
        if (Objects.equals(response.status(), 0) && !message.isEmpty() && !ENDED.matcher(message).find()) {
            return new OperationsVo.Incident(message, response.url(), delay, at);
        }
        if (delay) return new OperationsVo.Incident(!message.isEmpty() && !SUCCESS.matcher(message).find() ? message
                : "Train service is delayed. Please follow station announcements.", response.url(), true, at);
        return null;
    }

    public OperationsVo operations() {
        return new OperationsVo(status, settings.lines().stream().map(l -> new OperationsVo.Line(l.lineId(), incidents.get(l.lineId()))).toList());
    }

    public void stop() {
        running = false;
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("MTR poll did not stop");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted stopping MTR poll", error); }
    }
}
