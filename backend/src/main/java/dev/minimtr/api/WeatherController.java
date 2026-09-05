package dev.minimtr.api;

import dev.minimtr.service.WeatherService;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherController {
    private final WeatherService weather;

    public WeatherController(WeatherService weather) { this.weather = weather; }

    @GetMapping("/api/weather")
    public ResponseEntity<?> weather(@RequestParam(defaultValue = "zh") String lang) {
        if (!lang.equals("zh") && !lang.equals("en"))
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid lang; expected en or zh"));
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(weather.load(lang));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "Weather data unavailable"));
        }
    }
}
