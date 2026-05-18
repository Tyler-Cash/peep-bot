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
                RailAlert.Cause.UNKNOWN,
                List.of(atDate(EVENT_DATE, 0)),
                List.of(atDate(EVENT_DATE, 24)));
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
    void citywideBackboneRouteWithDisruptiveEffectMatches() {
        // TfNSW publishes effect but not severity. The Saturday 2026-05-16
        // metro alert had: routes=[SMNW_M1], stops=[], effect=MODIFIED_SERVICE,
        // severity=UNKNOWN. This must be surfaced.
        var items = filter.filter(
                List.of(alert(
                        "metro-trackwork",
                        Set.of(),
                        Set.of("SMNW_M1"),
                        RailAlert.Severity.UNKNOWN,
                        RailAlert.Effect.MODIFIED_SERVICE)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                null,
                EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.CITYWIDE_LINE);
    }

    @Test
    void citywideBackboneRouteWithoutDisruptiveEffectIsIgnored() {
        var items = filter.filter(
                List.of(alert(
                        "cosmetic", Set.of(), Set.of("SMNW_M1"), RailAlert.Severity.UNKNOWN, RailAlert.Effect.UNKNOWN)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                null,
                EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void nonBackboneRouteWithDisruptiveEffectIsIgnored() {
        var items = filter.filter(
                List.of(alert(
                        "light-rail",
                        Set.of(),
                        Set.of("LightRail_L1"),
                        RailAlert.Severity.UNKNOWN,
                        RailAlert.Effect.MODIFIED_SERVICE)),
                List.of(),
                VENUE_LAT,
                VENUE_LNG,
                null,
                EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void doonsideVenueGetsCitywideMetroAlert() {
        // Regression for 2026-05-16: event in Doonside (far from any metro
        // stop) must still receive a metro trackwork alert because it's a
        // citywide line outage.
        double doonsideLat = -33.7693;
        double doonsideLng = 150.8722;
        var items = filter.filter(
                List.of(alert(
                        "metro-saturday",
                        Set.of(),
                        Set.of("SMNW_M1"),
                        RailAlert.Severity.UNKNOWN,
                        RailAlert.Effect.MODIFIED_SERVICE)),
                List.of(),
                doonsideLat,
                doonsideLng,
                null,
                EVENT_DATE);
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.reason()).isEqualTo(Reason.CITYWIDE_LINE);
            assertThat(i.id()).isEqualTo("metro-saturday");
        });
    }

    @Test
    void cosmeticHeadlineIsSuppressedEvenOnMajorStation() {
        // Eastern Suburbs alert from 2026-05-18: trains still run between
        // their normal terminuses, the only "disruption" is extra stops.
        // TfNSW codes it as MODIFIED_SERVICE which would otherwise survive the
        // citywide rule, but the rider experience is unchanged.
        RailAlert cosmetic = new RailAlert(
                "esi-cosmetic",
                "Some trains make extra stops",
                "Nightly from 9:30PM to 1:30AM, some trains make extra stops at St Peters and Erskineville.",
                "https://transportnsw.info/alerts/esi-cosmetic",
                Set.of("CENTRAL"),
                Set.of("ESI_1a"),
                RailAlert.Severity.UNKNOWN,
                RailAlert.Effect.MODIFIED_SERVICE,
                RailAlert.Cause.MAINTENANCE,
                List.of(atDate(EVENT_DATE, 0)),
                List.of(atDate(EVENT_DATE, 24)));
        var items = filter.filter(List.of(cosmetic), List.of(), VENUE_LAT, VENUE_LNG, "CENTRAL", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void terminusTruncationOnSameLineIsKept() {
        // "Trains run between Hornsby and Central" is a real terminus
        // truncation — must NOT be filtered as cosmetic just because it shares
        // the MODIFIED_SERVICE effect with the cosmetic alerts.
        RailAlert real = new RailAlert(
                "nth-truncation",
                "Trains run between Hornsby and Central, platforms 1-14",
                "Monday and Tuesday\nNightly from 9:40PM to 1:30AM, trains run to a changed timetable.",
                "https://transportnsw.info/alerts/nth-truncation",
                Set.of("CENTRAL"),
                Set.of("NTH_1a"),
                RailAlert.Severity.UNKNOWN,
                RailAlert.Effect.MODIFIED_SERVICE,
                RailAlert.Cause.MAINTENANCE,
                List.of(atDate(EVENT_DATE, 0)),
                List.of(atDate(EVENT_DATE, 24)));
        var items = filter.filter(List.of(real), List.of(), VENUE_LAT, VENUE_LNG, "CENTRAL", EVENT_DATE);
        assertThat(items).hasSize(1);
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
                        RailAlert.Cause.UNKNOWN,
                        List.of(later),
                        List.of(later.plusSeconds(3600)))),
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

    @Test
    void liveMetroFixtureSurfacesSaturdayTrackworkForDoonside() throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/test/resources/tfnsw/metro-alerts-sample.pb"));
        var alerts = TfnswAlertsClient.parse(bytes, "metro");

        double doonsideLat = -33.7693;
        double doonsideLng = 150.8722;
        // The fixture's metro alert is active Sat 2026-05-16 02:00 AEST → Mon 02:00 AEST.
        LocalDate eventDate = LocalDate.of(2026, 5, 16);

        var items = filter.filter(alerts, List.of(), doonsideLat, doonsideLng, null, eventDate);

        assertThat(items).anySatisfy(i -> {
            assertThat(i.reason()).isEqualTo(Reason.CITYWIDE_LINE);
            assertThat(i.title()).contains("Buses replace metro services");
        });
    }
}
