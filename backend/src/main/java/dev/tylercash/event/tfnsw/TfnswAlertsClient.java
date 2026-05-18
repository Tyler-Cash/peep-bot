package dev.tylercash.event.tfnsw;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Cause;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Effect;
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
        return fetch("/v2/gtfs/alerts/sydneytrains", "sydneytrains");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchSydneyMetro() {
        return fetch("/v2/gtfs/alerts/metro", "sydneymetro");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchTripReplacements() {
        return fetch("/v2/gtfs/realtime/sydneytrains", "trip-replacements");
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
            List<Instant> startTimes = new ArrayList<>();
            List<Instant> endTimes = new ArrayList<>();
            if (a.getActivePeriodCount() == 0) {
                startTimes.add(Instant.EPOCH);
                endTimes.add(Instant.ofEpochSecond(FAR_FUTURE_SECONDS));
            } else {
                for (int i = 0; i < a.getActivePeriodCount(); i++) {
                    var period = a.getActivePeriod(i);
                    startTimes.add(period.getStart() > 0 ? Instant.ofEpochSecond(period.getStart()) : Instant.EPOCH);
                    endTimes.add(
                            period.getEnd() > 0
                                    ? Instant.ofEpochSecond(period.getEnd())
                                    : Instant.ofEpochSecond(FAR_FUTURE_SECONDS));
                }
            }
            Severity sev = mapSeverity(a.getSeverityLevel());
            Effect eff = mapEffect(a.getEffect());
            Cause cause = mapCause(a.getCause());
            out.add(new RailAlert(
                    entity.getId(), headline, desc, url, stops, routes, sev, eff, cause, startTimes, endTimes));
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

    private static Cause mapCause(Alert.Cause c) {
        return switch (c) {
            case OTHER_CAUSE -> Cause.OTHER;
            case TECHNICAL_PROBLEM -> Cause.TECHNICAL_PROBLEM;
            case STRIKE -> Cause.STRIKE;
            case DEMONSTRATION -> Cause.DEMONSTRATION;
            case ACCIDENT -> Cause.ACCIDENT;
            case HOLIDAY -> Cause.HOLIDAY;
            case WEATHER -> Cause.WEATHER;
            case MAINTENANCE -> Cause.MAINTENANCE;
            case CONSTRUCTION -> Cause.CONSTRUCTION;
            case POLICE_ACTIVITY -> Cause.POLICE_ACTIVITY;
            case MEDICAL_EMERGENCY -> Cause.MEDICAL_EMERGENCY;
            default -> Cause.UNKNOWN;
        };
    }

    private static Effect mapEffect(Alert.Effect e) {
        return switch (e) {
            case NO_SERVICE -> Effect.NO_SERVICE;
            case REDUCED_SERVICE -> Effect.REDUCED_SERVICE;
            case SIGNIFICANT_DELAYS -> Effect.SIGNIFICANT_DELAYS;
            case DETOUR -> Effect.DETOUR;
            case ADDITIONAL_SERVICE -> Effect.ADDITIONAL_SERVICE;
            case MODIFIED_SERVICE -> Effect.MODIFIED_SERVICE;
            case STOP_MOVED -> Effect.STOP_MOVED;
            case OTHER_EFFECT -> Effect.OTHER;
            default -> Effect.UNKNOWN;
        };
    }
}
