package dev.tylercash.event.tfnsw;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Severity;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class TfnswAlertsClient {
    private static final long FAR_FUTURE_SECONDS =
            Instant.now().plusSeconds(86_400L * 365).getEpochSecond();

    private final RestClient client;
    private final TfnswConfiguration cfg;

    public TfnswAlertsClient(@Qualifier("tfnswAlertsRestClient") RestClient client, TfnswConfiguration cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchSydneyTrains() {
        return fetch("/v1/gtfs/alerts/sydneytrains", "sydneytrains");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchSydneyMetro() {
        return fetch("/v1/gtfs/alerts/sydneymetro", "sydneymetro");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchTripReplacements() {
        return fetch("/v1/gtfs/realtime/sydneytrains", "trip-replacements");
    }

    private List<RailAlert> fetch(String path, String agency) {
        if (!cfg.isEnabled()) return List.of();
        try {
            byte[] body = client.get().uri(path).retrieve().body(byte[].class);
            return body == null ? List.of() : parse(body, agency);
        } catch (Exception e) {
            log.warn("TfNSW alerts fetch failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    static List<RailAlert> parse(byte[] bytes, String agency) throws Exception {
        FeedMessage feed = FeedMessage.parseFrom(bytes);
        List<RailAlert> out = new ArrayList<>();
        for (FeedEntity entity : feed.getEntityList()) {
            if (!entity.hasAlert()) continue;
            Alert a = entity.getAlert();
            Set<String> stops = new HashSet<>();
            Set<String> routes = new HashSet<>();
            for (EntitySelector sel : a.getInformedEntityList()) {
                if (sel.hasStopId()) stops.add(sel.getStopId());
                if (sel.hasRouteId()) routes.add(sel.getRouteId());
            }
            String headline = textOf(a.getHeaderText());
            String desc = textOf(a.getDescriptionText());
            String url = textOf(a.getUrl());
            Instant start = a.getActivePeriodCount() > 0 && a.getActivePeriod(0).getStart() > 0
                    ? Instant.ofEpochSecond(a.getActivePeriod(0).getStart())
                    : Instant.EPOCH;
            Instant end = a.getActivePeriodCount() > 0 && a.getActivePeriod(0).getEnd() > 0
                    ? Instant.ofEpochSecond(a.getActivePeriod(0).getEnd())
                    : Instant.ofEpochSecond(FAR_FUTURE_SECONDS);
            Severity sev = mapSeverity(a.getSeverityLevel());
            out.add(new RailAlert(entity.getId(), headline, desc, url, stops, routes, sev, start, end));
        }
        log.debug("Parsed {} alerts from {}", out.size(), agency);
        return out;
    }

    private static String textOf(TranslatedString ts) {
        return ts.getTranslationCount() > 0 ? ts.getTranslation(0).getText() : "";
    }

    private static Severity mapSeverity(Alert.SeverityLevel s) {
        return switch (s) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case SEVERE -> Severity.SEVERE;
            default -> Severity.UNKNOWN;
        };
    }
}
