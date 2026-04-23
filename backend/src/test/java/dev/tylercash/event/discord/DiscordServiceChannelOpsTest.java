package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DiscordServiceChannelOpsTest {
    private static final long GUILD_ID = 42L;
    private static final long CHANNEL_ID = 999L;

    private DiscordConfiguration config;
    private EmbedService embedService;
    private EventRepository eventRepository;
    private FeatureTogglesConfiguration featureToggles;
    private RateLimiter rateLimiter;
    private Clock clock;
    private JDA jda;
    private DiscordChannelService channelService;
    private DiscordMessageService messageService;
    private DiscordRoleService roleService;
    private DiscordAuthService authService;

    private DiscordService service;

    @BeforeEach
    void setUp() {
        config = new DiscordConfiguration();
        config.setGuildId(GUILD_ID);
        embedService = mock(EmbedService.class);
        eventRepository = mock(EventRepository.class);
        featureToggles = mock(FeatureTogglesConfiguration.class);
        rateLimiter = mock(RateLimiter.class);
        clock = Clock.fixed(ZonedDateTime.parse("2026-05-01T12:00:00Z").toInstant(), ZoneId.of("UTC"));
        jda = mock(JDA.class);
        channelService = mock(DiscordChannelService.class);
        messageService = mock(DiscordMessageService.class);
        roleService = mock(DiscordRoleService.class);
        authService = mock(DiscordAuthService.class);

        service = new DiscordService(
                config,
                embedService,
                eventRepository,
                featureToggles,
                rateLimiter,
                clock,
                jda,
                channelService,
                messageService,
                roleService,
                authService);
    }

    private Event eventAt(ZonedDateTime dt, String name) {
        Event e = new Event();
        e.setName(name);
        e.setCreator("tester");
        e.setDateTime(dt);
        return e;
    }

    @Test
    @DisplayName("createEventChannel fetches the outings category and creates a channel named from the event")
    void createEventChannel_createsInEventsCategory() {
        Event event = eventAt(ZonedDateTime.parse("2026-06-15T10:00:00+10:00[Australia/Sydney]"), "Brunch");
        Category category = mock(Category.class);
        TextChannel created = mock(TextChannel.class);
        when(channelService.getCategoryByName(GUILD_ID, "outings")).thenReturn(category);
        when(channelService.createTextChannel(eq(category), anyString())).thenReturn(created);

        TextChannel result = service.createEventChannel(event);

        assertThat(result).isSameAs(created);
        verify(channelService).createTextChannel(eq(category), eq(DiscordUtil.getChannelNameFromEvent(event)));
    }

    @Test
    @DisplayName("updateChannelName forwards the event channel id and derived name to the channel service")
    void updateChannelName_delegates() {
        Event event = eventAt(ZonedDateTime.parse("2026-07-01T10:00:00+10:00[Australia/Sydney]"), "Hike");
        event.setChannelId(CHANNEL_ID);

        service.updateChannelName(event);

        verify(channelService).setChannelName(CHANNEL_ID, DiscordUtil.getChannelNameFromEvent(event));
    }

    @Test
    @DisplayName("deleteEventChannel delegates to the channel service using the event's channel id")
    void deleteEventChannel_delegates() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setChannelId(CHANNEL_ID);

        service.deleteEventChannel(event);

        verify(channelService).deleteChannel(CHANNEL_ID);
    }

    @Test
    @DisplayName("deletePrivateEventChannel is a no-op when the event has no private channel")
    void deletePrivateEventChannel_noopWhenNull() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setPrivateChannelId(null);

        service.deletePrivateEventChannel(event);

        verifyNoInteractions(channelService);
    }

    @Test
    @DisplayName("deletePrivateEventChannel deletes via the channel service when a private channel is set")
    void deletePrivateEventChannel_deletesWhenPresent() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setPrivateChannelId(777L);

        service.deletePrivateEventChannel(event);

        verify(channelService).deleteChannel(777L);
    }

    @Test
    @DisplayName("createPrivateEventChannel is a no-op when a private channel already exists")
    void createPrivateEventChannel_noopWhenAlreadyExists() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setPrivateChannelId(555L);

        service.createPrivateEventChannel(event);

        verifyNoInteractions(channelService);
        verifyNoInteractions(jda);
    }

    @Test
    @DisplayName("createPrivateEventChannel rejects with 409 when event roles have not been created")
    void createPrivateEventChannel_conflictWhenRoleMissing() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setPrivateChannelId(null);
        event.setAcceptedRoleId(null);

        assertThatThrownBy(() -> service.createPrivateEventChannel(event))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Event roles have not been created yet");

        verify(channelService, never()).createPrivateTextChannel(any(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("archiveEventChannel moves the channel to the archive category and sorts it")
    void archiveEventChannel_movesAndSorts() {
        Event event = eventAt(ZonedDateTime.now(clock), "anything");
        event.setChannelId(CHANNEL_ID);

        TextChannel eventChannel = mock(TextChannel.class);
        Category archive = mock(Category.class);
        when(channelService.getTextChannel(CHANNEL_ID)).thenReturn(eventChannel);
        when(channelService.getCategoryByName(GUILD_ID, "outings-archive")).thenReturn(archive);

        service.archiveEventChannel(event);

        verify(channelService).moveChannelToCategory(eventChannel, archive);
        verify(channelService).sortChannelsByChannelName(archive);
    }
}
