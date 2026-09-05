package dev.minimtr.repo;

import dev.minimtr.model.dto.MtrResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MtrClient {
    private final HttpClient client;
    private final JsonMapper mapper;
    private final String endpoint;

    public MtrClient(HttpClient client, JsonMapper mapper,
            @Value("${mtr.endpoint:https://rt.data.gov.hk/v1/transport/mtr/getSchedule.php}") String endpoint) {
        this.client = client;
        this.mapper = mapper; this.endpoint = endpoint;
    }

    public MtrResponse schedule(String line, String station) throws IOException, InterruptedException {
        var uri = URI.create(endpoint + "?type=mtr&lang=EN&line=" + URLEncoder.encode(line, StandardCharsets.UTF_8)
                + "&sta=" + URLEncoder.encode(station, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(line + "-" + station + " returned HTTP " + response.statusCode());
        var result = mapper.readValue(response.body(), MtrResponse.class);
        if (result == null || result.status() == null) throw new IOException(line + "-" + station + ": missing MTR response status");
        return result;
    }

}
