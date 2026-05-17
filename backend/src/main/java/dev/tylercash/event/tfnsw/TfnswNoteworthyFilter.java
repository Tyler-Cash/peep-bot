package dev.tylercash.event.tfnsw;

import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pure function that decides which TfNSW alerts and live-traffic events are
 * noteworthy enough to post for a given Discord event.
 *
 * <p>Rail/Metro rules (any one matches):
 * <ul>
 *   <li>Alert affects the nearest known rail/metro station to the venue</li>
 *   <li>Alert affects any station returned by {@link GtfsStopsIndex#majorStopIds()}</li>
 *   <li>Alert is SEVERE and affects a Sydney Trains line or Sydney Metro line</li>
 * </ul>
 *
 * <p>Traffic rules (any one matches):
 * <ul>
 *   <li>Major event geometry within {@code majorEventRadiusKm} of the venue, time-overlapping</li>
 *   <li>Road closure on a motorway/arterial/sub-arterial within {@code closureRadiusKm} of the venue</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TfnswNoteworthyFilter {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final Set<String> ARTERIAL_CLASSES = Set.of("motorway", "arterial", "sub-arterial");
    private static final Set<String> CITYWIDE_LINE_PREFIXES = Set.of("T", "MET", "Sydney Trains", "Sydney Metro");

    private final TfnswConfiguration cfg;
    private final GtfsStopsIndex stopsIndex;

    public List<NoteworthyItem> filter(
            List<RailAlert> railAlerts,
            List<TrafficEvent> trafficEvents,
            double venueLat,
            double venueLng,
            String nearestStationId,
            LocalDate eventDate) {
        List<NoteworthyItem> out = new ArrayList<>();
        Instant dayStart = eventDate.atStartOfDay(SYDNEY).toInstant();
        Instant dayEnd = eventDate.plusDays(1).atStartOfDay(SYDNEY).toInstant();
        Set<String> majorIds = stopsIndex.majorStopIds();

        for (RailAlert a : railAlerts) {
            if (!overlaps(a.start(), a.end(), dayStart, dayEnd)) continue;
            Reason reason = null;
            if (nearestStationId != null && a.affectedStopIds().contains(nearestStationId)) {
                reason = Reason.NEAREST_STATION;
            } else if (!Collections.disjoint(a.affectedStopIds(), majorIds)) {
                reason = Reason.MAJOR_STATION;
            } else if (a.severity() == RailAlert.Severity.SEVERE && affectsCityWideLine(a.affectedRouteIds())) {
                reason = Reason.CITYWIDE_LINE;
            }
            if (reason != null) {
                out.add(new NoteworthyItem(Source.RAIL_METRO, a.id(), a.headline(), a.description(), a.url(), reason));
            }
        }

        for (TrafficEvent t : trafficEvents) {
            if (!overlaps(t.start(), t.end(), dayStart, dayEnd)) continue;
            double dKm = haversineKm(venueLat, venueLng, t.lat(), t.lng());
            Reason reason = null;
            if (t.kind() == TrafficEvent.Kind.MAJOR_EVENT && dKm <= cfg.getMajorEventRadiusKm()) {
                reason = Reason.MAJOR_EVENT_NEAR_VENUE;
            } else if (t.kind() == TrafficEvent.Kind.ROAD_CLOSURE
                    && dKm <= cfg.getClosureRadiusKm()
                    && t.roadClass() != null
                    && ARTERIAL_CLASSES.contains(t.roadClass().toLowerCase())) {
                reason = Reason.CLOSURE_NEAR_VENUE;
            }
            if (reason != null) {
                out.add(new NoteworthyItem(Source.TRAFFIC, t.id(), t.headline(), t.description(), t.url(), reason));
            }
        }
        return out;
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return !aStart.isAfter(bEnd) && !aEnd.isBefore(bStart);
    }

    private static boolean affectsCityWideLine(Set<String> routeIds) {
        return routeIds.stream().anyMatch(r -> CITYWIDE_LINE_PREFIXES.stream().anyMatch(r::startsWith));
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2)
                        * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    public record RailAlert(
            String id,
            String headline,
            String description,
            String url,
            Set<String> affectedStopIds,
            Set<String> affectedRouteIds,
            Severity severity,
            Effect effect,
            Instant start,
            Instant end) {
        public enum Severity {
            UNKNOWN,
            INFO,
            WARNING,
            SEVERE
        }

        public enum Effect {
            UNKNOWN,
            NO_SERVICE,
            REDUCED_SERVICE,
            SIGNIFICANT_DELAYS,
            DETOUR,
            ADDITIONAL_SERVICE,
            MODIFIED_SERVICE,
            STOP_MOVED,
            OTHER;

            /**
             * GTFS effects we treat as "service-disrupting" for the citywide
             * filter — populated by TfNSW for the great majority of alerts
             * (severity is not). Anything else is considered cosmetic for the
             * purpose of citywide posting.
             */
            public boolean isDisruptive() {
                return this == NO_SERVICE
                        || this == REDUCED_SERVICE
                        || this == SIGNIFICANT_DELAYS
                        || this == DETOUR
                        || this == MODIFIED_SERVICE;
            }
        }
    }

    public record TrafficEvent(
            String id,
            String headline,
            String description,
            String url,
            Kind kind,
            double lat,
            double lng,
            Instant start,
            Instant end,
            String roadClass) {
        public enum Kind {
            MAJOR_EVENT,
            ROAD_CLOSURE
        }
    }
}
