package dev.tylercash.event.event.statemachine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.immich.ImmichConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class EventStateMachineGuardsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private ImmichConfiguration immichConfig;
    private EventStateMachineGuards guards;

    @BeforeEach
    void setUp() {
        immichConfig = new ImmichConfiguration();
        guards = new EventStateMachineGuards(CLOCK, immichConfig);
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
    @DisplayName("preEventNotifyGuard: true when within 2h before event")
    void preEventNotify_withinWindow() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(1));
        assertTrue(guards.preEventNotifyGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("preEventNotifyGuard: false when event already started")
    void preEventNotify_afterEvent() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMinutes(30));
        assertFalse(guards.preEventNotifyGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("preEventNotifyGuard: false when more than 2h before event")
    void preEventNotify_tooEarly() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(3));
        assertFalse(guards.preEventNotifyGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("postAlbumGuard: true when Immich enabled and 1h+ after event")
    void postAlbum_enabled_afterOneHour() {
        immichConfig.setEnabled(true);
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(2));
        assertTrue(guards.postAlbumGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("postAlbumGuard: false when Immich disabled")
    void postAlbum_disabled() {
        immichConfig.setEnabled(false);
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(2));
        assertFalse(guards.postAlbumGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("postAlbumGuard: false when less than 1h after event")
    void postAlbum_tooSoon() {
        immichConfig.setEnabled(true);
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK));
        assertFalse(guards.postAlbumGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("completeFromNotifiedGuard: true when Immich disabled and 6h+ after event")
    void completeFromNotified_immichDisabled() {
        immichConfig.setEnabled(false);
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));
        assertTrue(guards.completeFromNotifiedGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("completeFromNotifiedGuard: false when Immich enabled")
    void completeFromNotified_immichEnabled() {
        immichConfig.setEnabled(true);
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));
        assertFalse(guards.completeFromNotifiedGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("completeGuard: true when 6h+ after event")
    void complete_afterSixHours() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));
        assertTrue(guards.completeGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("completeGuard: false when less than 6h after event")
    void complete_tooSoon() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(3));
        assertFalse(guards.completeGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("archiveGuard: true when past day+1 at 22:00")
    void archive_pastExpiry() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusDays(2));
        assertTrue(guards.archiveGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("archiveGuard: false when before day+1 at 22:00")
    void archive_beforeExpiry() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK));
        assertFalse(guards.archiveGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("deleteGuard: true when 3+ months after event")
    void delete_afterThreeMonths() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(4));
        assertTrue(guards.deleteGuard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("deleteGuard: false when less than 3 months after event")
    void delete_tooSoon() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(1));
        assertFalse(guards.deleteGuard().evaluate(contextWithEvent(event)));
    }
}
