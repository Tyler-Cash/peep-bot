package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

class DiscordUtilTest {

    public static Stream<Arguments> attendanceTitleGenerator() {
        return Stream.of(
                Arguments.of("✅ Accepted (Capacity 2/5)", "✅ Accepted", 2, 5),
                Arguments.of("✅ Accepted (2)", "✅ Accepted", 2, 0)
        );
    }

    public static Stream<Arguments> nameGenerator() {
        return Stream.of(
                Arguments.of(
                        "30th-dec-simple event 😊 super long name testing the max length 1234fffffffffffffffffffffffffff",
                        new Event(0, 0, 0, "Simple event 😊 super long name testing the max length 1234fffffffffffffffffffffffffff", "Lorem Ipsum is simply dummy text of the printing and typesetting industry. Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum. :) 😊", LocalDateTime.parse("2018-12-30T19:34:50.63")))
        );
    }

    @ParameterizedTest
    @MethodSource("attendanceTitleGenerator")
    void generateAttendanceTitle(String expected, String prefix, int count, int capacity) {
        Assertions.assertEquals(expected, DiscordUtil.generateAttendanceTitle(prefix, count, capacity));
    }

    @ParameterizedTest
    @MethodSource("nameGenerator")
    void getChannelNameFromEvent(String expectedName, Event event) {
        Assertions.assertEquals(expectedName, DiscordUtil.getChannelNameFromEvent(event));
    }
}