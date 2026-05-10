package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.util.List;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TfnswReportingServiceTest {

    @Test
    void embedHasTransportAndTrafficSections() {
        DiscordService discord = mock(DiscordService.class);
        TfnswReportingService svc = new TfnswReportingService(discord);

        Event e = new Event();
        e.setName("Test event");
        e.setChannelId(123L);

        svc.post(
                e,
                List.of(
                        new NoteworthyItem(
                                Source.RAIL_METRO,
                                "a1",
                                "T1 line suspended",
                                "Trackwork all day",
                                "https://x",
                                Reason.NEAREST_STATION),
                        new NoteworthyItem(
                                Source.TRAFFIC,
                                "e1",
                                "Marathon",
                                "Roads closed",
                                "https://y",
                                Reason.MAJOR_EVENT_NEAR_VENUE)),
                false);

        ArgumentCaptor<MessageEmbed> cap = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(discord).sendEmbedToEventChannel(eq(e), cap.capture());
        MessageEmbed embed = cap.getValue();
        assertThat(embed.getDescription())
                .contains("Transport")
                .contains("Traffic")
                .contains("T1 line suspended")
                .contains("Marathon");
        assertThat(embed.getTitle()).contains("Test event");
    }

    @Test
    void titlePrefixedWithUpdateWhenIsUpdate() {
        DiscordService discord = mock(DiscordService.class);
        TfnswReportingService svc = new TfnswReportingService(discord);

        Event e = new Event();
        e.setName("Trivia");
        e.setChannelId(1L);

        svc.post(e, List.of(new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.MAJOR_STATION)), true);

        ArgumentCaptor<MessageEmbed> cap = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(discord).sendEmbedToEventChannel(eq(e), cap.capture());
        assertThat(cap.getValue().getTitle()).startsWith("Update:");
    }

    @Test
    void emptyItemsListIsNoOp() {
        DiscordService discord = mock(DiscordService.class);
        new TfnswReportingService(discord).post(new Event(), List.of(), false);
        verify(discord, org.mockito.Mockito.never()).sendEmbedToEventChannel(any(), any());
    }
}
