package dev.minimtr.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceSettings(int schemaVersion, String serviceDayStart,
        int serviceEndOffsetMinutes, double dwellSeconds, double travelTimeFactor, List<Line> lines) {
    public ServiceSettings {
        if (schemaVersion != 1 || serviceEndOffsetMinutes <= 0 || lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Invalid mtr-service.json");
        }
        OffsetTime.parse(serviceDayStart);
        if (!Double.isFinite(dwellSeconds) || dwellSeconds < 0 || !positive(travelTimeFactor)) {
            throw new IllegalArgumentException("Invalid dwell time or travel time factor");
        }
        lines = List.copyOf(lines);
    }

    public LocalDate serviceDateAt(Instant at) {
        var start = OffsetTime.parse(serviceDayStart);
        var local = at.atOffset(start.getOffset());
        return local.toLocalTime().isBefore(start.toLocalTime())
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    public Instant startsAt(LocalDate date) {
        return date.atTime(OffsetTime.parse(serviceDayStart)).toInstant();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String lineId, String apiCode, String nameEn, String nameZh,
            String colour, String incidentAnchorStation, String monitorStation, double speedKmph,
            double firstTrainOffsetMinutes, double lastTrainOffsetMinutes,
            Headways headways, Map<String, Pattern> patterns) {
        public Line {
            if (!positive(speedKmph) || !Double.isFinite(firstTrainOffsetMinutes)
                    || firstTrainOffsetMinutes < 0 || !Double.isFinite(lastTrainOffsetMinutes)
                    || lastTrainOffsetMinutes < firstTrainOffsetMinutes || headways == null
                    || patterns == null || !patterns.keySet().containsAll(List.of("UP", "DOWN"))) {
                throw new IllegalArgumentException("Invalid schedule settings for " + lineId);
            }
            patterns = Map.copyOf(patterns);
        }
    }

    public record Headways(double peak, double normal, double evening, double late) {
        public Headways {
            if (!positive(peak) || !positive(normal) || !positive(evening) || !positive(late)) {
                throw new IllegalArgumentException("Headways must be finite and positive");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pattern(@JsonProperty("default") String defaultPattern, String alternate, Integer every) {
        public Pattern {
            if (defaultPattern == null || defaultPattern.isBlank()
                    || (alternate == null) != (every == null)
                    || (every != null && (every <= 0 || alternate.isBlank()))) {
                throw new IllegalArgumentException("Invalid route pattern selection");
            }
        }

        public String choose(int sequence) {
            return every != null && sequence % every == 0 ? alternate : defaultPattern;
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
