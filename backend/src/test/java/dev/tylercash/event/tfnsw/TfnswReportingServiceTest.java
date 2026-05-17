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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TfnswReportingServiceTest {

    private final DiscordService discord = mock(DiscordService.class);
    private final TfnswReportingService reporter = new TfnswReportingService(discord);

    @Test
    void firstPostFormatsBulletsWithHumanLineNamesAndReturnsMessageId() {
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.sendContentToEventChannel(eq(event), any(String.class))).thenReturn(123_456L);

        var items = List.of(
                new NoteworthyItem(
                        Source.RAIL_METRO,
                        "metro-1",
                        "Buses replace metro services between Tallawong and Chatswood",
                        "Use trains between Chatswood and Sydenham",
                        "https://transportnsw.info/alerts/metro-1",
                        Reason.CITYWIDE_LINE,
                        Set.of("SMNW_M1")),
                new NoteworthyItem(
                        Source.RAIL_METRO,
                        "trains-1",
                        "Trackwork between Hornsby and Strathfield",
                        "Buses replace trains",
                        "https://transportnsw.info/alerts/trains-1",
                        Reason.CITYWIDE_LINE,
                        Set.of("NTH_1a", "NTH_1b")));

        Long id = reporter.post(event, items);
        assertThat(id).isEqualTo(123_456L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(discord).sendContentToEventChannel(eq(event), captor.capture());
        String expected = "🚧 Transport notice — trackwork or disruption may affect travel to this event:\n"
                + "• **Sydney Metro Northwest** — Buses replace metro services between Tallawong and Chatswood "
                + "(<https://transportnsw.info/alerts/metro-1>)\n"
                + "• **Northern Line** — Trackwork between Hornsby and Strathfield "
                + "(<https://transportnsw.info/alerts/trains-1>)";
        assertThat(captor.getValue()).isEqualTo(expected);
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

        var newItems = List.of(new NoteworthyItem(
                Source.RAIL_METRO,
                "trains-2",
                "T1 reduced timetable",
                "Friday and Saturday only",
                "https://transportnsw.info/alerts/trains-2",
                Reason.CITYWIDE_LINE,
                Set.of("T1")));

        Boolean replied = reporter.postUpdate(event, 999L, newItems);
        assertThat(replied).isTrue();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(discord).replyToMessage(eq(event), eq(999L), captor.capture());
        String expected = "⚠️ Update — additional disruption since the previous notice:\n"
                + "• **T1 Western** — T1 reduced timetable "
                + "(<https://transportnsw.info/alerts/trains-2>)";
        assertThat(captor.getValue()).isEqualTo(expected);
    }

    @Test
    void updateReturnsFalseWhenReplyFailed() {
        Event event = new Event();
        event.setChannelId(1L);
        when(discord.replyToMessage(eq(event), anyLong(), any(String.class))).thenReturn(false);
        var newItems = List.of(new NoteworthyItem(
                Source.RAIL_METRO,
                "trains-2",
                "T1 reduced timetable",
                "Friday",
                "https://transportnsw.info/alerts/trains-2",
                Reason.CITYWIDE_LINE,
                Set.of("T1")));
        assertThat(reporter.postUpdate(event, 999L, newItems)).isFalse();
    }
}
