package dev.tylercash.event.tfnsw;

import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pure function that decides which TfNSW alerts and live-traffic events are
 * noteworthy enough to post for a given Discord event.
 *
 * <p>Everything is first gated on time: an alert only counts if one of its
 * active periods overlaps the event's travel window ({@code travelLeadHours}
 * before the start to {@code travelTailHours} after it), so early-morning
 * trackwork doesn't attach to an evening event on the same calendar day.
 *
 * <p>Rail/Metro rules (any one matches):
 * <ul>
 *   <li>Alert affects the nearest known rail/metro station to the venue</li>
 *   <li>Alert affects any station returned by {@link GtfsStopsIndex#majorStopIds()}</li>
 *   <li>Alert touches a backbone Sydney Trains or Sydney Metro line
 *       (see {@link BackboneRoutes}) and its GTFS effect is service-disrupting
 *       (no service, reduced service, significant delays, detour, modified
 *       service). TfNSW does not populate {@code severity_level}, so this
 *       rule is gated on {@code effect} instead.</li>
 * </ul>
 *
 * <p>Traffic rules (any one matches):
 * <ul>
 *   <li>Major event geometry within {@code majorEventRadiusKm} of the venue</li>
 *   <li>Road closure on a motorway/arterial/sub-arterial within {@code closureRadiusKm} of the venue</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TfnswNoteworthyFilter {
    private static final Set<String> ARTERIAL_CLASSES = Set.of("motorway", "arterial", "sub-arterial");
    /** "Some trains make extra stops", "Trains make an extra stop at Ashfield", ... */
    private static final Pattern EXTRA_STOPS = Pattern.compile("^(some )?trains make (an )?extra stops?\\b");

    private final TfnswConfiguration cfg;
    private final GtfsStopsIndex stopsIndex;

    public List<NoteworthyItem> filter(
            List<RailAlert> railAlerts,
            List<TrafficEvent> trafficEvents,
            double venueLat,
            double venueLng,
            String nearestStationId,
            ZonedDateTime eventStart) {
        List<NoteworthyItem> out = new ArrayList<>();
        Instant start = eventStart.toInstant();
        Instant windowStart = start.minus(Duration.ofHours(cfg.getTravelLeadHours()));
        Instant windowEnd = start.plus(Duration.ofHours(cfg.getTravelTailHours()));
        Instant staleStartBefore = windowStart.minus(Duration.ofHours(cfg.getOpenEndedLeadHours()));
        Instant openEndedAfter = start.plus(Duration.ofDays(cfg.getStandingHorizonDays()));
        Set<String> majorIds = stopsIndex.majorStopIds();

        for (RailAlert a : railAlerts) {
            if (!hasMeaningfulOverlap(
                    a.startTimes(), a.endTimes(), windowStart, windowEnd, staleStartBefore, openEndedAfter)) continue;
            if (isCosmeticHeadline(a.headline())) continue;
            Reason reason = null;
            // Station rules surface an alert on location alone, so they must also
            // confirm something is actually disrupted — otherwise informational
            // "Station Update" notices at a major/nearest station leak through.
            if (nearestStationId != null && a.affectedStopIds().contains(nearestStationId)) {
                if (disruptiveEnough(a)) reason = Reason.NEAREST_STATION;
            } else if (!Collections.disjoint(a.affectedStopIds(), majorIds)) {
                if (disruptiveEnough(a)) reason = Reason.MAJOR_STATION;
            } else if (BackboneRoutes.touches(a.affectedRouteIds())
                    && a.effect().isDisruptive()) {
                reason = Reason.CITYWIDE_LINE;
            }
            if (reason != null) {
                out.add(new NoteworthyItem(
                        Source.RAIL_METRO,
                        a.id(),
                        a.headline(),
                        a.description(),
                        a.url(),
                        reason,
                        a.affectedRouteIds(),
                        a.cause(),
                        a.startTimes(),
                        a.endTimes()));
            }
        }

        for (TrafficEvent t : trafficEvents) {
            if (!overlaps(t.start(), t.end(), windowStart, windowEnd)) continue;
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
                out.add(new NoteworthyItem(
                        Source.TRAFFIC,
                        t.id(),
                        t.headline(),
                        t.description(),
                        t.url(),
                        reason,
                        Set.of(),
                        null,
                        List.of(t.start()),
                        List.of(t.end())));
            }
        }
        return out;
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return !aStart.isAfter(bEnd) && !aEnd.isBefore(bStart);
    }

    /**
     * True if at least one active period overlaps the travel window with a
     * period that isn't "stale standing". A period is stale standing when it is
     * both open-ended — ending on/after {@code openEndedAfter}, i.e. carrying no
     * genuine end (TfNSW defaults a missing end to ~a year out) — and started
     * before {@code staleStartBefore}. An open-ended alert is either a live
     * incident, which says nothing about travel days from now, or a
     * never-closed notice; both would otherwise attach to every event in the
     * open window, so they only count when the event is imminent.
     */
    private static boolean hasMeaningfulOverlap(
            List<Instant> startTimes,
            List<Instant> endTimes,
            Instant windowStart,
            Instant windowEnd,
            Instant staleStartBefore,
            Instant openEndedAfter) {
        for (int i = 0; i < startTimes.size(); i++) {
            Instant start = startTimes.get(i);
            Instant end = endTimes.get(i);
            if (!overlaps(start, end, windowStart, windowEnd)) continue;
            boolean openEnded = !end.isBefore(openEndedAfter);
            boolean staleStart = start.isBefore(staleStartBefore);
            if (openEnded && staleStart) continue;
            return true;
        }
        return false;
    }

    /**
     * Strong-disruption keywords: their presence in a headline means the rider
     * experience is materially affected (replacements, closures, cancellations,
     * suspensions, truncations), regardless of the GTFS {@code effect} field —
     * which TfNSW often leaves UNKNOWN. Used both to keep an alert that the
     * cosmetic filter would otherwise drop and to satisfy the station-rule
     * disruption gate.
     */
    private static final List<String> STRONG_LANGUAGE = List.of(
            "do not run",
            "buses replace",
            " only",
            "start and end",
            "no service",
            "delays",
            "cancelled",
            "closed",
            "suspended",
            "between ");

    static boolean hasStrongDisruptionLanguage(String headline) {
        if (headline == null) return false;
        String lower = headline.toLowerCase().strip();
        for (String keyword : STRONG_LANGUAGE) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean disruptiveEnough(RailAlert a) {
        return a.effect().isDisruptive() || hasStrongDisruptionLanguage(a.headline());
    }

    /**
     * Drops alerts that are effectively cosmetic — trains are still running on
     * normal terminuses, the only "disruption" is extra stopping patterns or a
     * shuffled timetable. TfNSW publishes these with GTFS effect
     * MODIFIED_SERVICE which our citywide rule otherwise treats as
     * service-disrupting, but the rider experience is unchanged. We strip them
     * by headline shape because the GTFS effect alone can't tell us apart from
     * a real terminus truncation. Anything with stronger language ("buses
     * replace", "do not run", "between X and Y only", "start and end at",
     * "delays", "cancelled", "closed") falls through the early-return and is
     * kept.
     */
    static boolean isCosmeticHeadline(String headline) {
        if (headline == null) return false;
        String lower = headline.toLowerCase().strip();
        if (lower.isEmpty()) return false;
        if (hasStrongDisruptionLanguage(lower)) return false;
        return EXTRA_STOPS.matcher(lower).find() || lower.startsWith("trains run to a changed timetable");
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
            Cause cause,
            List<Instant> startTimes,
            List<Instant> endTimes) {
        public enum Severity {
            UNKNOWN,
            INFO,
            WARNING,
            SEVERE
        }

        /**
         * GTFS-R Alert.Cause subset we care about. Used to label alert clusters
         * (e.g. MAINTENANCE → "Trackwork") when two or more alerts share the
         * same cause and active-period end times — that combination is a
         * reliable fingerprint for "downstream of the same planned job."
         */
        public enum Cause {
            UNKNOWN,
            OTHER,
            TECHNICAL_PROBLEM,
            STRIKE,
            DEMONSTRATION,
            ACCIDENT,
            HOLIDAY,
            WEATHER,
            MAINTENANCE,
            CONSTRUCTION,
            POLICE_ACTIVITY,
            MEDICAL_EMERGENCY
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
