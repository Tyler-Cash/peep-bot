package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EventInitCompleteListenerTest {

    private ImmichService immichService;
    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private EventLifecyclePublisher publisher;
    private EventInitCompleteListener listener;

    private final UUID eventId = UUID.randomUUID();
    private Event event;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        immichService = mock(ImmichService.class);
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        publisher = mock(EventLifecyclePublisher.class);

        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);

        listener = new EventInitCompleteListener(
                immichService, discordService, eventRepository, eventServiceProvider, publisher);

        event = new Event();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
    }

    @Test
    void happyPath_createsAlbumAndShareKey_thenSetsPlannedAndPublishes() throws Exception {
        // event has no album or share key
        assertThat(event.getImmichAlbumId()).isNull();
        assertThat(event.getImmichShareKey()).isNull();

        when(immichService.createAlbum(any(), any())).thenReturn(Optional.of("album-123"));
        when(immichService.createSharedLink("album-123")).thenReturn(Optional.of("share-key"));

        listener.handle(new EventLifecycleEvent.EventClassified(eventId));

        verify(immichService).createAlbum(any(), any());
        verify(immichService).createSharedLink("album-123");
        assertThat(event.getImmichAlbumId()).isEqualTo("album-123");
        assertThat(event.getImmichShareKey()).isEqualTo("share-key");

        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
        assertThat(event.getState()).isEqualTo(EventState.PLANNED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventPlanned(event.getId()));
    }

    @Test
    void idempotentPath_skipsAlbumCreation_whenAlbumAndShareKeyAlreadySet() throws Exception {
        event.setImmichAlbumId("existing-album");
        event.setImmichShareKey("existing-key");

        listener.handle(new EventLifecycleEvent.EventClassified(eventId));

        verify(immichService, never()).createAlbum(any(), any());
        verify(immichService, never()).createSharedLink(any());

        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
        assertThat(event.getState()).isEqualTo(EventState.PLANNED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventPlanned(event.getId()));
    }

    @Test
    void albumCreationFails_exceptionSwallowed_stateStillPlannedAndPublished() throws Exception {
        when(immichService.createAlbum(any(), any())).thenThrow(new RuntimeException("Immich unavailable"));

        listener.handle(new EventLifecycleEvent.EventClassified(eventId));

        assertThat(event.getState()).isEqualTo(EventState.PLANNED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventPlanned(event.getId()));
    }
}
