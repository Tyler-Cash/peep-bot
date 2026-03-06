package dev.tylercash.event.event.processor;

import dev.tylercash.event.GlobalTestConfiguration;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostAlbumLinkTest {
    private static final Clock clock = GlobalTestConfiguration.CLOCK;

    private static ImmichConfiguration enabledImmichConfig() {
        ImmichConfiguration config = new ImmichConfiguration();
        config.setEnabled(true);
        return config;
    }

    private static PostAlbumLink createProcessor() {
        return new PostAlbumLink(
                mock(DiscordService.class),
                mock(EventRepository.class),
                enabledImmichConfig(),
                mock(ImmichService.class),
                clock
        );
    }

    private static PostAlbumLink createProcessor(DiscordService discordService, EventRepository eventRepository,
                                                  ImmichService immichService) {
        return new PostAlbumLink(discordService, eventRepository, enabledImmichConfig(), immichService, clock);
    }

    @Test
    @DisplayName("Event 1h+ after start with share key and no notification should be processed")
    void shouldProcessEventAfterOneHour() {
        PostAlbumLink processor = createProcessor();
        Event event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).minusHours(2));
        event.setImmichShareKey("test-key");
        assertTrue(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event before 1h mark should be skipped")
    void shouldSkipEventBeforeOneHour() {
        PostAlbumLink processor = createProcessor();
        Event event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock));
        assertFalse(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event already notified should be skipped")
    void shouldSkipAlreadyNotifiedEvent() {
        PostAlbumLink processor = createProcessor();
        Event event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).minusHours(2));
        event.getNotifications().add(new Notification(NotificationType.ALBUM_LINK, Instant.now(), 123L));
        assertFalse(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Archived event should be skipped")
    void shouldSkipArchivedEvent() {
        PostAlbumLink processor = createProcessor();
        Event event = new Event();
        event.setState(EventState.ARCHIVED);
        event.setDateTime(ZonedDateTime.now(clock).minusHours(2));
        assertFalse(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event with null share key and null album ID should attempt creation")
    void shouldAttemptCreationWhenBothNull() {
        DiscordService discordService = mock(DiscordService.class);
        EventRepository eventRepository = mock(EventRepository.class);
        ImmichService immichService = mock(ImmichService.class);
        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
        when(immichService.createSharedLink("album-id")).thenReturn(Optional.of("share-key"));
        when(immichService.getShareUrl("share-key")).thenReturn("https://immich.example.com/share/share-key");

        PostAlbumLink processor = createProcessor(discordService, eventRepository, immichService);

        Event event = new Event();
        event.setName("Test Event");
        event.setDescription("Test");
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).minusHours(2));

        processor.processEvent(event);

        verify(immichService).createAlbum("Test Event", "Test");
        verify(immichService).createSharedLink("album-id");
        verify(discordService).sendAlbumLink(eq(event), eq("https://immich.example.com/share/share-key"));
    }
}
