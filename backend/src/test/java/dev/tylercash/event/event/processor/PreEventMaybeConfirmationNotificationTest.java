package dev.tylercash.event.event.processor;

import dev.tylercash.event.GlobalTestConfiguration;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PreEventMaybeConfirmationNotificationTest {
    private static final Clock clock = GlobalTestConfiguration.CLOCK;

    public static Stream<Arguments> eventNotificationGenerator() {
        List<Arguments> arguments = new ArrayList<>();

        Event event = new Event();
        event.setState(EventState.ARCHIVED);
        event.setDateTime(ZonedDateTime.now(clock).plusHours(5));
        arguments.add(Arguments.of(Named.of("Check archived events can't be notified", event)));

        event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).minusMinutes(30));
        arguments.add(Arguments.of(Named.of("Event already occurred 30 minutes before, don't notify", event)));

        event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).plusDays(1).withHour(7));
        arguments.add(Arguments.of(Named.of("Event is more than 6 hours away, don't notify", event)));

        return arguments.stream();
    }

    private static @NotNull PreEventMaybeConfirmationNotification getNotifyBeforeEventItemProcessor() {
        return new PreEventMaybeConfirmationNotification(
                mock(DiscordService.class), mock(EventRepository.class), clock);
    }

    @ParameterizedTest
    @MethodSource("eventNotificationGenerator")
    void shouldEventBeProcessedFailure(Event event) {
        PreEventMaybeConfirmationNotification preEventMaybeConfirmationNotification = getNotifyBeforeEventItemProcessor();
        assertFalse(preEventMaybeConfirmationNotification.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event starting in 5 hours")
    void shouldEventBeProcessedSuccess1() {
        PreEventMaybeConfirmationNotification preEventMaybeConfirmationNotification = getNotifyBeforeEventItemProcessor();
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(clock).plusHours(5));
        assertTrue(preEventMaybeConfirmationNotification.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event is today at 9am")
    void shouldEventBeProcessedSuccess2() {
        PreEventMaybeConfirmationNotification preEventMaybeConfirmationNotification = getNotifyBeforeEventItemProcessor();
        Event event = new Event();
        ZonedDateTime now = ZonedDateTime.now(clock);
        event.setDateTime(now.withHour(9));

        // set clock to 8am
        Clock fixedClock = Clock.fixed(now.withHour(8).toInstant(), now.getZone());

        PreEventMaybeConfirmationNotification preEventMaybeConfirmationNotificationWithFixedClock = new PreEventMaybeConfirmationNotification(
            mock(DiscordService.class), mock(EventRepository.class), fixedClock);

        assertTrue(preEventMaybeConfirmationNotificationWithFixedClock.shouldEventBeProcessed(event));
    }
}
