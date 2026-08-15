package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Прод-источник расписаний: бесплатный datasets-CDN DefiLlama (платный /emissions отдаёт 402).
 * `emissionsProtocolsList` → список slug; `emissions/{slug}` → расписание. Подтверждено живьём (doc 50).
 */
public class DefiLlamaEmissionSource implements EmissionSource {

    private static final String BASE = "https://defillama-datasets.llama.fi/";
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("User-Agent", "curl/8.0").timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " " + path);
        return mapper.readTree(r.body());
    }

    @Override public List<String> protocols() throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode n : get("emissionsProtocolsList")) out.add(n.asText());
        return out;
    }

    @Override public JsonNode emissions(String slug) throws Exception {
        return get("emissions/" + slug);
    }
}
