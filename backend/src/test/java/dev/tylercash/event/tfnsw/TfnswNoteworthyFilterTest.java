package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TfnswNoteworthyFilterTest {
    private static final double VENUE_LAT = -33.8688;
    private static final double VENUE_LNG = 151.2093;
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 5, 17);

    private final TfnswConfiguration cfg = defaultCfg();
    private final GtfsStopsIndex stopsIndex = indexWithMajors("CENTRAL", "TOWN_HALL");
    private final TfnswNoteworthyFilter filter = new TfnswNoteworthyFilter(cfg, stopsIndex);

    private static TfnswConfiguration defaultCfg() {
        TfnswConfiguration c = new TfnswConfiguration();
        c.setNearestStationRadiusKm(1.5);
        c.setMajorEventRadiusKm(5.0);
        c.setClosureRadiusKm(2.0);
        return c;
    }

    private static GtfsStopsIndex indexWithMajors(String... ids) {
        GtfsStopsIndex idx = new GtfsStopsIndex(new TfnswConfiguration(), null);
        idx.setStopsForTest(java.util.List.of(), java.util.Set.of(ids));
        return idx;
    }

    private static Instant atDate(LocalDate d, int hourOffset) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(hourOffset * 3600L);
    }

    private static RailAlert alert(String id, Set<String> stops, Set<String> routes, RailAlert.Severity sev) {
        return alert(id, stops, routes, sev, RailAlert.Effect.UNKNOWN);
    }

    private static RailAlert alert(
            String id, Set<String> stops, Set<String> routes, RailAlert.Severity sev, RailAlert.Effect eff) {
        return new RailAlert(
                id,
                "Headline",
                "Detail",
                "https://transportnsw.info/alerts/" + id,
                stops,
                routes,
                sev,
                eff,
                atDate(EVENT_DATE, 0),
                atDate(EVENT_DATE, 24));
    }

    @Test
    void railAlertOnNearestStationMatches() {
        var items = filter.filter(
                List.of(alert("a1", Set.of("TOWN_HALL"), Set.of(), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                "TOWN_HALL",
                EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.NEAREST_STATION);
    }

    @Test
    void railAlertOnMajorStationMatches() {
        var items = filter.filter(
                List.of(alert("a2", Set.of("CENTRAL"), Set.of(), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                "ZZZ_NOT_MAJOR",
                EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_STATION);
    }

    @Test
    void severeCityWideMatches() {
        var items = filter.filter(
                List.of(alert("a3", Set.of("UNKNOWN_STOP"), Set.of("T1"), RailAlert.Severity.SEVERE)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                "ZZZ",
                EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.CITYWIDE_LINE);
    }

    @Test
    void minorAlertOnUnrelatedStationSkipped() {
        var items = filter.filter(
                List.of(alert("a4", Set.of("UNKNOWN_STOP"), Set.of(), RailAlert.Severity.INFO)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                "OTHER",
                EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void alertOutsideEventDateSkipped() {
        Instant later = atDate(EVENT_DATE.plusDays(30), 0);
        var items = filter.filter(
                List.of(new RailAlert(
                        "a5",
                        "x",
                        "y",
                        "u",
                        Set.of("CENTRAL"),
                        Set.of(),
                        RailAlert.Severity.WARNING,
                        RailAlert.Effect.UNKNOWN,
                        later,
                        later.plusSeconds(3600))),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                "ZZZ",
                EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void majorEventNearVenueMatches() {
        var t = new TrafficEvent(
                "e1",
                "Marathon",
                "City roads closed",
                "u",
                TrafficEvent.Kind.MAJOR_EVENT,
                -33.8700,
                151.2100,
                atDate(EVENT_DATE, 0),
                atDate(EVENT_DATE, 24),
                null);
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_EVENT_NEAR_VENUE);
    }

    @Test
    void majorEventFarFromVenueSkipped() {
        var t = new TrafficEvent(
                "e2",
                "Marathon",
                "...",
                "u",
                TrafficEvent.Kind.MAJOR_EVENT,
                -34.5,
                150.5,
                atDate(EVENT_DATE, 0),
                atDate(EVENT_DATE, 24),
                null);
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void roadClosureOnArterialNearVenueMatches() {
        var t = new TrafficEvent(
                "c1",
                "George St closed",
                "...",
                "u",
                TrafficEvent.Kind.ROAD_CLOSURE,
                -33.8695,
                151.2090,
                atDate(EVENT_DATE, 0),
                atDate(EVENT_DATE, 24),
                "arterial");
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.CLOSURE_NEAR_VENUE);
    }

    @Test
    void roadClosureOnLocalRoadSkipped() {
        var t = new TrafficEvent(
                "c2",
                "Side st closed",
                "...",
                "u",
                TrafficEvent.Kind.ROAD_CLOSURE,
                -33.8695,
                151.2090,
                atDate(EVENT_DATE, 0),
                atDate(EVENT_DATE, 24),
                "local");
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void noNearestStationStillRunsRailRules() {
        // When nearestStationId is null, major-station + severe-citywide rules still apply.
        var items = filter.filter(
                List.of(alert("a6", Set.of("CENTRAL"), Set.of(), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                null,
                EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_STATION);
    }
}
