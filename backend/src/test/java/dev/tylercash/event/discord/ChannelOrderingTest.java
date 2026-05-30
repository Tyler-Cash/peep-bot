package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChannelOrderingTest {

    private static TextChannel channel(long id) {
        TextChannel channel = mock(TextChannel.class);
        lenient().when(channel.getIdLong()).thenReturn(id);
        return channel;
    }

    private static TextChannel named(long id, String name) {
        TextChannel channel = channel(id);
        lenient().when(channel.getName()).thenReturn(name);
        return channel;
    }

    private static List<Long> sortedIds(List<TextChannel> input, java.util.Comparator<? super TextChannel> order) {
        List<TextChannel> copy = new ArrayList<>(input);
        copy.sort(order);
        return copy.stream().map(TextChannel::getIdLong).toList();
    }

    @Test
    @DisplayName("byEventDate orders event channels by their event timestamp")
    void byEventDate_ordersByTimestamp() {
        TextChannel later = channel(2);
        TextChannel earlier = channel(4);
        Map<Long, ZonedDateTime> eventTime = Map.of(
                2L, ZonedDateTime.parse("2026-06-20T10:00:00Z"),
                4L, ZonedDateTime.parse("2026-06-10T10:00:00Z"));

        List<Long> result = sortedIds(List.of(later, earlier), ChannelOrdering.byEventDate(eventTime, Map.of()));

        assertThat(result).containsExactly(4L, 2L);
    }

    @Test
    @DisplayName("byEventDate sorts across the year boundary by instant, not month-day")
    void byEventDate_sortsAcrossYearBoundary() {
        TextChannel dec = channel(1);
        TextChannel jan = channel(2);
        Map<Long, ZonedDateTime> eventTime = Map.of(
                1L, ZonedDateTime.parse("2025-12-30T10:00:00Z"),
                2L, ZonedDateTime.parse("2026-01-01T10:00:00Z"));

        List<Long> result = sortedIds(List.of(jan, dec), ChannelOrdering.byEventDate(eventTime, Map.of()));

        assertThat(result).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("byEventDate floats orphan channels to the front, preserving their existing relative order")
    void byEventDate_orphansFirstStable() {
        TextChannel orphanA = channel(1);
        TextChannel event = channel(2);
        TextChannel orphanB = channel(3);
        Map<Long, ZonedDateTime> eventTime = Map.of(2L, ZonedDateTime.parse("2026-06-10T10:00:00Z"));

        // input order: orphanA, event, orphanB
        List<Long> result =
                sortedIds(List.of(orphanA, event, orphanB), ChannelOrdering.byEventDate(eventTime, Map.of()));

        assertThat(result).containsExactly(1L, 3L, 2L);
    }

    @Test
    @DisplayName("byEventDate keeps each private channel immediately after its main event channel")
    void byEventDate_privateFollowsItsMain() {
        TextChannel mainEarly = channel(10);
        TextChannel privateEarly = channel(11);
        TextChannel mainLate = channel(20);
        TextChannel privateLate = channel(21);
        Map<Long, ZonedDateTime> eventTime = Map.of(
                10L, ZonedDateTime.parse("2026-06-10T10:00:00Z"),
                20L, ZonedDateTime.parse("2026-06-20T10:00:00Z"));
        Map<Long, Long> privateToMain = Map.of(11L, 10L, 21L, 20L);

        // input order deliberately scrambled
        List<Long> result = sortedIds(
                List.of(mainLate, privateEarly, mainEarly, privateLate),
                ChannelOrdering.byEventDate(eventTime, privateToMain));

        assertThat(result).containsExactly(10L, 11L, 20L, 21L);
    }

    @Test
    @DisplayName("byChannelName orders archive channels by parsed day-month, foreign channels last")
    void byChannelName_ordersByMonthDayForeignLast() {
        TextChannel mar = named(1, "15-mar-hike");
        TextChannel janEarly = named(2, "01-jan-brunch");
        TextChannel janLate = named(3, "05-jan-dinner");
        TextChannel foreign = named(4, "general");

        List<Long> result = sortedIds(List.of(mar, foreign, janEarly, janLate), ChannelOrdering.byChannelName());

        assertThat(result).containsExactly(2L, 3L, 1L, 4L);
    }
}
