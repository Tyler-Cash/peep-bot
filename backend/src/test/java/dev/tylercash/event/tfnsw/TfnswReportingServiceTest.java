package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Cause;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TfnswReportingServiceTest {

    private final DiscordService discord = mock(DiscordService.class);
    private final TfnswReportingService reporter = new TfnswReportingService(discord);

    private static NoteworthyItem rail(String id, String title, String detail, String url, Set<String> routes) {
        return new NoteworthyItem(
                Source.RAIL_METRO,
                id,
                title,
                detail,
                url,
                Reason.CITYWIDE_LINE,
                routes,
                Cause.UNKNOWN,
                List.of(),
                List.of());
    }

    private static NoteworthyItem railWithPeriods(String id, List<Instant> starts, List<Instant> ends) {
        return new NoteworthyItem(
                Source.RAIL_METRO,
                id,
                "Title",
                "Detail",
                "",
                Reason.MAJOR_STATION,
                Set.of("T1"),
                Cause.MAINTENANCE,
                starts,
                ends);
    }

    @Test
    void clusterTimeWindowRendersFromStartWhenEndIsOpenEnded() {
        // An open-ended period (TfNSW defaults a missing end to ~a year out) must
        // not render its synthetic end as a real time-of-day. Fri 2026-05-15
        // 15:00 Sydney start, far-future end → "Fri 15 May, from 3PM".
        Instant start = Instant.parse("2026-05-15T05:00:00Z");
        Instant farFuture = Instant.parse("2027-06-01T06:46:00Z");
        var a = railWithPeriods("a", List.of(start), List.of(farFuture));
        var b = railWithPeriods("b", List.of(start), List.of(farFuture));

        String window = TfnswReportingService.clusterTimeWindow(List.of(a, b));

        assertThat(window).contains("Fri 15 May");
        assertThat(window).contains("from 3PM");
        assertThat(window).doesNotContain("–");
        assertThat(window).doesNotContain("2027");
        assertThat(window).doesNotContain("4:46");
    }

    @Test
    void clusterTimeWindowRendersRangeForBoundedOvernightPeriod() {
        // Mon 2026-05-18 21:40 Sydney → Tue 01:30 Sydney. Labelled by the night
        // it starts (Mon 18), with a real end time.
        Instant start = Instant.parse("2026-05-18T11:40:00Z");
        Instant end = Instant.parse("2026-05-18T15:30:00Z");
        var a = railWithPeriods("a", List.of(start), List.of(end));
        var b = railWithPeriods("b", List.of(start), List.of(end));

        String window = TfnswReportingService.clusterTimeWindow(List.of(a, b));

        assertThat(window).isEqualTo("Mon 18 May, 9:40PM–1:30AM");
    }

    @Test
    void firstPostFormatsBulletsWithHumanLineNamesAndReturnsMessageId() {
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.sendContentToEventChannel(eq(event), any(String.class))).thenReturn(123_456L);

        var items = List.of(
                rail(
                        "metro-1",
                        "Buses replace metro services between Tallawong and Chatswood",
                        "Use trains between Chatswood and Sydenham",
                        "https://transportnsw.info/alerts/metro-1",
                        Set.of("SMNW_M1")),
                rail(
                        "trains-1",
                        "Trackwork between Hornsby and Strathfield",
                        "Buses replace trains",
                        "https://transportnsw.info/alerts/trains-1",
                        Set.of("NTH_1a", "NTH_1b")));

        Long id = reporter.post(event, items);
        assertThat(id).isEqualTo(123_456L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(discord).sendContentToEventChannel(eq(event), captor.capture());
        String expected = "🚧 Transport notice — trackwork or disruption may affect travel to this event:\n"
                + "• Sydney Metro Northwest — Buses replace metro services between Tallawong and Chatswood "
                + "([details](https://transportnsw.info/alerts/metro-1))\n"
                + "  Use trains between Chatswood and Sydenham\n"
                + "• Northern Line — Trackwork between Hornsby and Strathfield "
                + "([details](https://transportnsw.info/alerts/trains-1))\n"
                + "  Buses replace trains";
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void pinDelegatesToDiscordService() {
        Event event = new Event();
        event.setChannelId(1L);

        reporter.pin(event, 4242L);

        verify(discord).pinMessageInEventChannel(event, 4242L);
    }

    @Test
    void pinSwallowsDiscordFailures() {
        Event event = new Event();
        event.setChannelId(1L);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(discord)
                .pinMessageInEventChannel(any(), org.mockito.ArgumentMatchers.anyLong());

        reporter.pin(event, 4242L); // must not throw
    }

    @Test
    void firstPostSkippedWhenNoItems() {
        Event event = new Event();
        Long id = reporter.post(event, List.of());
        assertThat(id).isNull();
        verifyNoInteractions(discord);
    }

    @Test
    void updateRepliesWithDeltaBulletsOnly() {
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.replyToMessage(eq(event), eq(999L), any(String.class))).thenReturn(true);

        var newItems = List.of(rail(
                "trains-2",
                "T1 reduced timetable",
                "Friday and Saturday only",
                "https://transportnsw.info/alerts/trains-2",
                Set.of("T1")));

        Boolean replied = reporter.postUpdate(event, 999L, newItems);
        assertThat(replied).isTrue();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(discord).replyToMessage(eq(event), eq(999L), captor.capture());
        String expected = "⚠️ Update — additional disruption since the previous notice:\n"
                + "• T1 reduced timetable ([details](https://transportnsw.info/alerts/trains-2))\n"
                + "  Friday and Saturday only";
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void updateReturnsFalseWhenReplyFailed() {
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.replyToMessage(eq(event), anyLong(), any(String.class))).thenReturn(false);
        var newItems = List.of(rail(
                "trains-2",
                "T1 reduced timetable",
                "Friday",
                "https://transportnsw.info/alerts/trains-2",
                Set.of("T1")));
        assertThat(reporter.postUpdate(event, 999L, newItems)).isFalse();
    }

    @Test
    void railItemsSharingCauseAndEndTimesCollapseIntoOneCluster() {
        // Two alerts with cause=MAINTENANCE and matching end-times across both
        // active periods (the canonical "same City Circle trackwork job"
        // fingerprint) must render under one banner with sub-bullets. A third
        // alert in the same batch with no shared end-times stays as its own
        // top-level bullet.
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.sendContentToEventChannel(eq(event), any(String.class))).thenReturn(1L);

        // Mon 2026-05-18 21:40 Sydney → 2026-05-19 01:30 Sydney; same shape
        // again the following night. Sydney is UTC+10 in May (no DST).
        List<Instant> sharedStarts =
                List.of(Instant.parse("2026-05-18T11:40:00Z"), Instant.parse("2026-05-19T11:40:00Z"));
        List<Instant> sharedEnds =
                List.of(Instant.parse("2026-05-18T15:30:00Z"), Instant.parse("2026-05-19T15:30:00Z"));

        var clusterA = new NoteworthyItem(
                Source.RAIL_METRO,
                "ems-71595",
                "City Circle: Trains do not run on the City Circle, or between Central and Wynyard",
                "Mon 18 and Tue 19 May\nNightly from 9:40PM to 1:30AM, ...",
                "https://transportnsw.info/alerts/ems-71595",
                Reason.CITYWIDE_LINE,
                Set.of("APS_1a"),
                Cause.MAINTENANCE,
                sharedStarts,
                sharedEnds);
        var clusterB = new NoteworthyItem(
                Source.RAIL_METRO,
                "ems-71634",
                "Trains run between Hornsby and Central, platforms 1-14",
                "Mon 18 and Tue 19 May\nNightly from 9:40PM to 1:30AM, ...",
                "https://transportnsw.info/alerts/ems-71634",
                Reason.CITYWIDE_LINE,
                Set.of("NTH_1a"),
                Cause.MAINTENANCE,
                sharedStarts,
                sharedEnds);
        // Same cause but a different end-time → standalone.
        var standalone = new NoteworthyItem(
                Source.RAIL_METRO,
                "ems-99999",
                "T1 separate trackwork",
                "Wed 20 May\nNightly from 9:40PM to 2:00AM, ...",
                "https://transportnsw.info/alerts/ems-99999",
                Reason.CITYWIDE_LINE,
                Set.of("WST_1a"),
                Cause.MAINTENANCE,
                List.of(Instant.parse("2026-05-20T11:40:00Z")),
                List.of(Instant.parse("2026-05-20T16:00:00Z")));

        reporter.post(event, List.of(clusterA, clusterB, standalone));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(discord).sendContentToEventChannel(eq(event), captor.capture());
        String body = captor.getValue();
        assertThat(body).contains("🚧 Trackwork — Mon 18 / Tue 19 May, 9:40PM–1:30AM (2 alerts):");
        assertThat(body).contains("↳ Airport & South Line — City Circle:");
        assertThat(body).contains("↳ Northern Line — Trains run between Hornsby and Central");
        // Standalone bullet uses the normal "•" prefix and keeps its time
        // subline, since there's no banner above it to carry the window.
        assertThat(body).contains("• T1 separate trackwork");
        assertThat(body).contains("  Wed 20 May — Nightly from 9:40PM to 2:00AM,");
    }
}
