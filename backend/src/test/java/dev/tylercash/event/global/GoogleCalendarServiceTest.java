package dev.tylercash.event.global;

import dev.tylercash.event.event.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GoogleCalendarServiceTest {
    private static final ZonedDateTime time = ZonedDateTime.of(
            LocalDateTime.of(2024, 8, 16, 13, 24),
            ZoneId.systemDefault()
    );

    public static Stream<Arguments> calendarTestEvents() {
        return Stream.of(
                new Event(0, 0, 0, "Simple event 😊", "creator", time, ""),
                new Event(0, 0, 0, "Simple event", "creator", time, "")
        ).map(Arguments::of);
    }

    @DisplayName("Calendar URL parsing")
    @ParameterizedTest
    @MethodSource("calendarTestEvents")
    void getCalendarEventUrlSuccess(Event event) {
        String calendarEventUrl = GoogleCalendarService.getCalendarEventUrl(event);
        assertNotNull(calendarEventUrl);
        assertEquals(
                UriComponentsBuilder.fromHttpUrl("https://www.google.com/calendar/event" +
                                "?action=TEMPLATE" +
                                "&text=" + event.getName() +
                                "&dates=20240816T132400Z/20240816T142400Z")
                        .encode()
                        .build()
                        .toUri()
                        .toString(),
                calendarEventUrl
        );
    }

}
