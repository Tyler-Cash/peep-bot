package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImmichAlbumPrepListenerTest {

    private ImmichConfiguration immichConfiguration;
    private ImmichService immichService;
    private EventRepository eventRepository;
    private ImmichAlbumPrepListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        immichConfiguration = mock(ImmichConfiguration.class);
        immichService = mock(ImmichService.class);
        eventRepository = mock(EventRepository.class);
        listener = new ImmichAlbumPrepListener(immichConfiguration, immichService, eventRepository);
    }

    @Test
    void disabledImmich_skipsAllWork() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(false);

        listener.handle(new EventLifecycleEvent.EventPreNotified(eventId));

        verifyNoInteractions(immichService, eventRepository);
    }

    @Test
    void happyPath_createsAlbumAndSharedLink() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(true);

        Event event = new Event();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(immichService.createAlbum(any(), any())).thenReturn(Optional.of("a"));
        when(immichService.createSharedLink("a")).thenReturn(Optional.of("k"));

        listener.handle(new EventLifecycleEvent.EventPreNotified(eventId));

        // Listener writes via a narrow column update so a slow Immich call
        // can't clobber concurrent edits to other Event fields.
        verify(eventRepository).updateImmichAlbumDetails(event.getId(), "a", "k");
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void idempotent_skipsWorkWhenAlbumAndKeyAlreadySet() throws Exception {
        when(immichConfiguration.isEnabled()).thenReturn(true);

        Event event = new Event();
        event.setImmichAlbumId("x");
        event.setImmichShareKey("y");
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventPreNotified(eventId));

        verify(immichService, never()).createAlbum(any(), any());
        verify(immichService, never()).createSharedLink(any());
    }

    @Test
    void createAlbumReturnsEmpty_throwsIllegalStateException() {
        when(immichConfiguration.isEnabled()).thenReturn(true);

        Event event = new Event();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(immichService.createAlbum(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.handle(new EventLifecycleEvent.EventPreNotified(eventId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Album preparation incomplete");
    }
}
