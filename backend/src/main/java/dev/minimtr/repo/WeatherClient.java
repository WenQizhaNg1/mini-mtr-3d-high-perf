package dev.minimtr.repo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WeatherClient {
    private final HttpClient client;
    private final JsonMapper mapper;
    private final String endpoint;

    public WeatherClient(HttpClient client, JsonMapper mapper,
            @Value("${weather.endpoint:https://data.weather.gov.hk/weatherAPI/opendata/weather.php}") String endpoint) {
        this.client = client;
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    public JsonNode current(String language) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?dataType=rhrread&lang="
                + (language.equals("zh") ? "tc" : "en"))).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HKO weather returned HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

}
