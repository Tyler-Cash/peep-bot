package dev.tylercash.event.discord;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

class DiscordUtilTest {

    public static Stream<Arguments> attendanceTitleGenerator() {
        return Stream.of(
                Arguments.of("✅ Accepted (Capacity 2/5)", "✅ Accepted", 2, 5)
        );
    }

    @ParameterizedTest
    @MethodSource("attendanceTitleGenerator")
    void generateAttendanceTitle(String expected, String prefix, int count, int capacity) {
        assertEquals(
                expected,
                DiscordUtil.generateAttendanceTitle(prefix, count, capacity)
        );

    }
}