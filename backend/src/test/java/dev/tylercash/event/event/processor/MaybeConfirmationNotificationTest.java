package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MaybeConfirmationNotificationTest {

    private static Stream<Event> eventFailureGenerator() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T06:00:00Z"), ZoneId.of("UTC"));

        Event event7HoursAway = new Event();
        event7HoursAway.setDateTime(ZonedDateTime.now(fixedClock).plusHours(7));

        Event event1Pm = new Event();
        event1Pm.setDateTime(ZonedDateTime.now(fixedClock).withHour(13));

        Event eventStarted = new Event();
        eventStarted.setDateTime(ZonedDateTime.now(fixedClock).minusHours(1));

        Event eventArchived = new Event();
        eventArchived.setState(EventState.ARCHIVED);
        eventArchived.setDateTime(ZonedDateTime.now(fixedClock).plusHours(1));

        Event notificationSent = new Event();
        notificationSent.setDateTime(ZonedDateTime.now(fixedClock).plusHours(1));
        notificationSent.getNotifications().add(new Notification(NotificationType.MAYBE_CONFIRMATION));

        return Stream.of(event7HoursAway, event1Pm, eventStarted, eventArchived, notificationSent);
    }

    private MaybeConfirmationNotification getProcessor(Clock clock) {
        return new MaybeConfirmationNotification(
                mock(DiscordService.class),
                mock(EventRepository.class),
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("eventFailureGenerator")
    @DisplayName("Failure scenarios")
    void shouldEventBeProcessedFailure(Event event) {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T06:00:00Z"), ZoneId.of("UTC"));
        MaybeConfirmationNotification processor = getProcessor(fixedClock);
        assertFalse(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event is 5 hours away")
    void shouldEventBeProcessedSuccess1() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T12:00:00Z"), ZoneId.of("UTC"));
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(fixedClock).plusHours(5));
        MaybeConfirmationNotification processor = getProcessor(fixedClock);
        assertTrue(processor.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event is at 9 AM on the same day")
    void shouldEventBeProcessedSuccess2() {
        Clock fixedClock = Clock.fixed(Instant.parse("2025-01-01T08:30:00Z"), ZoneId.of("UTC"));
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(fixedClock).withHour(9));
        MaybeConfirmationNotification processor = getProcessor(fixedClock);
        assertTrue(processor.shouldEventBeProcessed(event));
    }
}
