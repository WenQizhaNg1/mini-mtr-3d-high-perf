package dev.minimtr.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MtrResponse(Integer status, String message, String url, String isdelay,
        @JsonProperty("curr_time") String currentTime, Map<String, Station> data) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(@JsonProperty("curr_time") String currentTime,
            @JsonProperty("UP") List<Eta> up, @JsonProperty("DOWN") List<Eta> down) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Eta(String dest, String time, String valid) {}
}
