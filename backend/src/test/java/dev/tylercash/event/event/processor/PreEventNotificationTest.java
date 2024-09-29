package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import io.github.resilience4j.ratelimiter.RateLimiter;
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

class PreEventNotificationTest {
    private static final Clock clock = Clock.systemDefaultZone();

    public static Stream<Arguments> eventNotificationGenerator() {
        List<Arguments> arguments = new ArrayList<>();

        Event event = new Event();
        event.setState(EventState.ARCHIVED);
        event.setDateTime(ZonedDateTime.now(clock).minusMinutes(30));
        arguments.add(Arguments.of(Named.of("Check archived events can't be notified", event)));

        event = new Event();
        event.setState(EventState.PLANNED);
        event.setDateTime(ZonedDateTime.now(clock).plusMinutes(1));
        arguments.add(Arguments.of(Named.of("Event already occurred 30 minutes before, don't notify", event)));

        return arguments.stream();
    }

    private static @NotNull PreEventNotification getNotifyBeforeEventItemProcessor() {
        return new PreEventNotification(
                mock(DiscordService.class), mock(EventRepository.class), mock(RateLimiter.class), clock);
    }

    @ParameterizedTest
    @MethodSource("eventNotificationGenerator")
    void shouldEventBeProcessedFailure(Event event) {
        PreEventNotification preEventNotification = getNotifyBeforeEventItemProcessor();
        assertFalse(preEventNotification.shouldEventBeProcessed(event));
    }

    @Test
    @DisplayName("Event starting in 1.5h")
    void shouldEventBeProcessedSuccess() {
        PreEventNotification preEventNotification = getNotifyBeforeEventItemProcessor();
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(clock).minusHours(1).minusMinutes(30));
        assertTrue(preEventNotification.shouldEventBeProcessed(event));
    }
}