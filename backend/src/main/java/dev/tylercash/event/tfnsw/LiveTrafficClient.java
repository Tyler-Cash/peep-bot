package dev.tylercash.event.tfnsw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent.Kind;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class LiveTrafficClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;
    private final TfnswConfiguration cfg;

    public LiveTrafficClient(@Qualifier("tfnswLiveTrafficRestClient") RestClient client, TfnswConfiguration cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    @CircuitBreaker(name = "tfnsw")
    public List<TrafficEvent> fetchMajorEvents() {
        return fetch("/v2/live/hazards/majorevent/open", LiveTrafficClient::parseMajorEvents);
    }

    @CircuitBreaker(name = "tfnsw")
    public List<TrafficEvent> fetchHazards() {
        return fetch("/v2/live/hazards/incident/open", LiveTrafficClient::parseHazards);
    }

    private List<TrafficEvent> fetch(String path, Function<String, List<TrafficEvent>> parser) {
        if (!cfg.isEnabled()) return List.of();
        try {
            String body = client.get().uri(path).retrieve().body(String.class);
            return body == null ? List.of() : parser.apply(body);
        } catch (Exception e) {
            log.warn("TfNSW live-traffic fetch failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    static List<TrafficEvent> parseMajorEvents(String json) {
        return parseFeatures(json, Kind.MAJOR_EVENT);
    }

    static List<TrafficEvent> parseHazards(String json) {
        return parseFeatures(json, Kind.ROAD_CLOSURE);
    }

    private static List<TrafficEvent> parseFeatures(String json, Kind kind) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<TrafficEvent> out = new ArrayList<>();
            for (JsonNode f : root.path("features")) {
                JsonNode props = f.path("properties");
                if (kind == Kind.ROAD_CLOSURE
                        && !"roadClosure"
                                .equalsIgnoreCase(props.path("mainCategory").asText())) {
                    continue;
                }
                JsonNode coords = f.path("geometry").path("coordinates");
                double lng = firstNumber(coords, 0);
                double lat = firstNumber(coords, 1);
                String roadClass =
                        props.path("roads").isArray() && props.path("roads").size() > 0
                                ? props.path("roads").get(0).path("classOfRoad").asText(null)
                                : null;
                out.add(new TrafficEvent(
                        props.path("id").asText(),
                        props.path("displayName").asText(""),
                        props.path("description").asText(""),
                        props.path("webLinkUrl").asText(""),
                        kind,
                        lat,
                        lng,
                        parseTime(props.path("start").asText(null)),
                        parseTime(props.path("end").asText(null)),
                        roadClass));
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse GeoJSON: {}", e.getMessage());
            return List.of();
        }
    }

    private static double firstNumber(JsonNode arr, int i) {
        if (arr.isArray() && arr.size() > i && arr.get(i).isNumber())
            return arr.get(i).asDouble();
        if (arr.isArray() && arr.size() > 0 && arr.get(0).isArray()) return firstNumber(arr.get(0), i);
        return 0.0;
    }

    private static Instant parseTime(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
