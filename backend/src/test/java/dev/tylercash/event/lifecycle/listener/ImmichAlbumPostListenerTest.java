package dev.tylercash.event.lifecycle.listener;

import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ImmichAlbumPostListenerTest {

    private ImmichConfiguration immichConfiguration;
    private ImmichService immichService;
    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private ImmichAlbumPostListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        immichConfiguration = mock(ImmichConfiguration.class);
        immichService = mock(ImmichService.class);
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        listener = new ImmichAlbumPostListener(
                immichConfiguration, immichService, discordService, eventRepository, eventServiceProvider);
    }

    @Test
    void disabledImmich_skipsAllWork() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(false);

        listener.handle(new EventLifecycleEvent.EventCompleted(eventId));

        verifyNoInteractions(immichService, discordService, eventRepository);
    }

    @Test
    void noShareKey_skipsAlbumPost() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(true);

        Event event = new Event();
        // immichShareKey is null by default
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCompleted(eventId));

        verifyNoInteractions(immichService, discordService);
    }

    @Test
    void happyPath_postsAlbumLinkAndUpdatesMessage() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(true);

        Event event = new Event();
        event.setImmichShareKey("share-key-abc");
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(immichService.getShareUrl("share-key-abc")).thenReturn("https://share/abc");

        listener.handle(new EventLifecycleEvent.EventCompleted(eventId));

        verify(discordService).sendAlbumLink(event, "https://share/abc");
        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
    }
}
