package dev.tylercash.event.discord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.util.Comparator;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.restaction.order.CategoryOrderAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordChannelServiceReorderTest {

    private static final Comparator<GuildChannel> BY_ID = Comparator.comparingLong(GuildChannel::getIdLong);

    private DiscordChannelService service;

    @BeforeEach
    void setUp() {
        service = new DiscordChannelService(mock(JDA.class), ObservationRegistry.NOOP);
    }

    private static TextChannel channel(long id) {
        TextChannel channel = mock(TextChannel.class);
        lenient().when(channel.getIdLong()).thenReturn(id);
        return channel;
    }

    @Test
    @DisplayName("reorderChannels issues no Discord call when channels are already in the desired order")
    void reorderChannels_noOpWhenAlreadyOrdered() {
        TextChannel a = channel(1);
        TextChannel b = channel(2);
        TextChannel c = channel(3);
        Category category = mock(Category.class);
        when(category.getTextChannels()).thenReturn(List.of(a, b, c));

        service.reorderChannels(category, BY_ID);

        verify(category, never()).modifyTextChannelPositions();
        verify(a, never()).getManager();
        verify(b, never()).getManager();
        verify(c, never()).getManager();
    }

    @Test
    @DisplayName("reorderChannels issues exactly one bulk reorder when the order differs")
    void reorderChannels_singleBulkReorderWhenUnordered() {
        TextChannel a = channel(1);
        TextChannel b = channel(2);
        TextChannel c = channel(3);
        Category category = mock(Category.class);
        // current order is reversed relative to BY_ID
        when(category.getTextChannels()).thenReturn(List.of(c, b, a));
        CategoryOrderAction order = mock(CategoryOrderAction.class);
        when(category.modifyTextChannelPositions()).thenReturn(order);
        when(order.sortOrder(any())).thenReturn(order);

        service.reorderChannels(category, BY_ID);

        verify(category, times(1)).modifyTextChannelPositions();
        verify(order, times(1)).sortOrder(BY_ID);
        // no per-channel position PATCH — the N+1 we are eliminating
        verify(a, never()).getManager();
        verify(b, never()).getManager();
        verify(c, never()).getManager();
    }
}
