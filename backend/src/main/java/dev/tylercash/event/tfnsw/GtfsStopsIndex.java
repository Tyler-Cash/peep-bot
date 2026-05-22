package dev.tylercash.event.tfnsw;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Spatial index of Sydney rail + metro parent stations, loaded directly from the
 * TfNSW GTFS static schedule feed at startup (and refreshed daily). The feed is
 * the same source of truth that GTFS-R alerts reference via stop_id, so we never
 * hold a stale or hand-curated copy.
 *
 * <p>If {@link TfnswConfiguration#isEnabled()} is false (no API key) the index
 * stays empty and the feature degrades to "no rail/metro matches". Fetch
 * failures are logged but never block app startup — the next scheduled refresh
 * retries.
 */
@Slf4j
@Component
public class GtfsStopsIndex {
    /**
     * Curated set of citywide-hub stop_names; the GTFS feed's authoritative
     * stop_ids for these are looked up at refresh time. Names must match the
     * stop_name column exactly (case-insensitive, trimmed).
     */
    private static final Set<String> MAJOR_STATION_NAMES = Set.of(
            "central station",
            "town hall station",
            "wynyard station",
            "redfern station",
            "chatswood station",
            "parramatta station",
            "strathfield station");

    public record Stop(String stopId, String name, double lat, double lng) {}

    private final TfnswConfiguration cfg;
    private final RestClient client;

    private volatile List<Stop> stops = List.of();
    private volatile Set<String> majorIds = Set.of();

    public GtfsStopsIndex(TfnswConfiguration cfg, @Qualifier("tfnswAlertsRestClient") RestClient client) {
        this.cfg = cfg;
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /** Daily refresh keeps the index aligned with GTFS feed changes (renumbers, new stations). */
    @Scheduled(cron = "0 0 4 * * *", zone = "Australia/Sydney")
    public void scheduledRefresh() {
        refresh();
    }

    public void refresh() {
        if (!cfg.isEnabled()) {
            log.info("TfNSW disabled — skipping GTFS stops index load");
            return;
        }
        List<Stop> all = new ArrayList<>();
        all.addAll(fetchAgency("Sydney Trains", cfg.getSydneyTrainsSchedulePath()));
        all.addAll(fetchAgency("Sydney Metro", cfg.getSydneyMetroSchedulePath()));
        if (all.isEmpty()) return;
        Set<String> majors = all.stream()
                .filter(s -> MAJOR_STATION_NAMES.contains(s.name().toLowerCase()))
                .map(Stop::stopId)
                .collect(Collectors.toUnmodifiableSet());
        this.stops = List.copyOf(all);
        this.majorIds = majors;
        log.info("Loaded {} TfNSW parent stops ({} major) from GTFS schedule feed", stops.size(), majorIds.size());
        if (majorIds.size() < MAJOR_STATION_NAMES.size()) {
            log.warn(
                    "Major-station resolution incomplete — matched {}/{} names. "
                            + "Unmatched names will be silently ignored; check stop_name spelling against the feed.",
                    majorIds.size(),
                    MAJOR_STATION_NAMES.size());
        }
    }

    private List<Stop> fetchAgency(String label, String path) {
        try {
            return fetchAndParseStops(path);
        } catch (Exception e) {
            log.warn("TfNSW stops fetch failed for {} ({}): {}", label, path, e.toString());
            return List.of();
        }
    }

    @CircuitBreaker(name = "tfnsw")
    public List<Stop> fetchAndParseStops(String path) throws IOException {
        byte[] zipBytes = client.get().uri(path).retrieve().body(byte[].class);
        return zipBytes == null ? List.of() : parseStopsFromZip(zipBytes);
    }

    public Set<String> majorStopIds() {
        return majorIds;
    }

    public Optional<Stop> findNearest(double lat, double lng, double radiusKm) {
        Stop best = null;
        double bestKm = Double.MAX_VALUE;
        for (Stop s : stops) {
            double d = TfnswNoteworthyFilter.haversineKm(lat, lng, s.lat(), s.lng());
            if (d <= radiusKm && d < bestKm) {
                best = s;
                bestKm = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Test seam: directly install a stop list (used by filter/orchestrator unit tests). */
    void setStopsForTest(List<Stop> all, Set<String> majors) {
        this.stops = List.copyOf(all);
        this.majorIds = Set.copyOf(majors);
    }

    static List<Stop> parseStopsFromZip(byte[] zipBytes) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("stops.txt".equals(e.getName())) {
                    return parseStopsCsv(new String(zin.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return List.of();
    }

    static List<Stop> parseStopsCsv(String csv) {
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return List.of();
        String[] header = lines[0].replace("﻿", "").toLowerCase().split(",", -1);
        int idIdx = indexOf(header, "stop_id");
        int nameIdx = indexOf(header, "stop_name");
        int latIdx = indexOf(header, "stop_lat");
        int lngIdx = indexOf(header, "stop_lon");
        int typeIdx = indexOf(header, "location_type");
        if (idIdx < 0 || nameIdx < 0 || latIdx < 0 || lngIdx < 0) {
            log.warn("stops.txt missing required GTFS columns; header: {}", lines[0]);
            return List.of();
        }
        List<Stop> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String[] f = line.split(",", -1);
            if (f.length <= Math.max(latIdx, lngIdx)) continue;
            // Only retain parent stations — that's what GTFS-R alerts reference. If the
            // feed leaves location_type blank for a row, include it (some agencies do).
            if (typeIdx >= 0 && f.length > typeIdx) {
                String t = unquote(f[typeIdx]);
                if (!t.isEmpty() && !"1".equals(t)) continue;
            }
            try {
                out.add(new Stop(
                        unquote(f[idIdx]),
                        unquote(f[nameIdx]),
                        Double.parseDouble(unquote(f[latIdx])),
                        Double.parseDouble(unquote(f[lngIdx]))));
            } catch (NumberFormatException ignored) {
                // skip malformed row
            }
        }
        return out;
    }

    private static int indexOf(String[] header, String key) {
        for (int i = 0; i < header.length; i++) {
            if (unquote(header[i]).equals(key)) return i;
        }
        return -1;
    }

    // Live TfNSW stops.txt quotes every field ("stop_id","stop_code",...). Strip a single
    // pair of surrounding double quotes after trimming whitespace.
    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
