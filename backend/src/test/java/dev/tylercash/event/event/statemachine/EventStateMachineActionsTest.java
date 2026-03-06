package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.immich.ImmichService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.ExtendedState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventStateMachineActionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private ImmichService immichService;
    private EventStateMachineActions actions;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        immichService = mock(ImmichService.class);
        actions = new EventStateMachineActions(discordService, eventRepository, immichService, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private StateContext<EventState, EventStateMachineEvent> contextWithEvent(Event event) {
        StateContext<EventState, EventStateMachineEvent> ctx = mock(StateContext.class);
        ExtendedState extState = mock(ExtendedState.class);
        when(ctx.getExtendedState()).thenReturn(extState);
        when(extState.get("event", Event.class)).thenReturn(event);
        return ctx;
    }

    @Test
    @DisplayName("preEventNotifyAction sends notification and sets state to NOTIFIED")
    void preEventNotify() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(1));

        actions.preEventNotifyAction().execute(contextWithEvent(event));

        verify(discordService).sendMessageBeforeEvent(event);
        assertEquals(EventState.NOTIFIED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("postAlbumAction posts album link and sets state to ALBUM_POSTED")
    void postAlbum() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(2));
        event.setImmichAlbumId("album-id");
        event.setImmichShareKey("share-key");

        when(immichService.getShareUrl("share-key")).thenReturn("https://immich.example.com/share/share-key");

        actions.postAlbumAction().execute(contextWithEvent(event));

        verify(discordService).sendAlbumLink(eq(event), eq("https://immich.example.com/share/share-key"));
        assertEquals(EventState.ALBUM_POSTED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("postAlbumAction creates album if missing")
    void postAlbum_createsAlbum() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(2));

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
        when(immichService.createSharedLink("album-id")).thenReturn(Optional.of("share-key"));
        when(immichService.getShareUrl("share-key")).thenReturn("https://immich.example.com/share/share-key");

        actions.postAlbumAction().execute(contextWithEvent(event));

        verify(immichService).createAlbum("Test", "Desc");
        verify(immichService).createSharedLink("album-id");
        assertEquals(EventState.ALBUM_POSTED, event.getState());
    }

    @Test
    @DisplayName("completeAction removes buttons and sets state to COMPLETED")
    void complete() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));

        actions.completeAction().execute(contextWithEvent(event));

        verify(discordService).removeEventButtons(event);
        assertEquals(EventState.COMPLETED, event.getState());
        assertTrue(event.getNotifications().stream()
                .anyMatch(n -> n.getType() == NotificationType.ATTENDANCE_LOCKED));
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("archiveAction archives channel and sets state to ARCHIVED")
    void archive() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusDays(2));

        actions.archiveAction().execute(contextWithEvent(event));

        verify(discordService).archiveEventChannel(event);
        assertEquals(EventState.ARCHIVED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("deleteAction deletes channel and sets state to DELETED")
    void delete() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(4));

        actions.deleteAction().execute(contextWithEvent(event));

        verify(discordService).deleteEventChannel(event);
        assertEquals(EventState.DELETED, event.getState());
        verify(eventRepository).save(event);
    }
}
