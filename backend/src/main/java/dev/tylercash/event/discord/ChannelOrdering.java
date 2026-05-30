package dev.tylercash.event.discord;

import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

/**
 * Pure {@link Comparator} factories describing how a category's channels should be ordered.
 *
 * <p>These produce a total order over the channels currently in a category; the actual
 * (minimal, bulk) reorder is applied by {@code DiscordChannelService.reorderChannels}.
 * Keeping the ordering policy here — free of JDA round-trips and Spring wiring — means it
 * can be unit-tested directly.
 */
@NoArgsConstructor
public final class ChannelOrdering {

    /**
     * Order the active events category by each channel's event timestamp.
     *
     * <ul>
     *   <li>Channels with no associated event ("orphans" — including a guild's separator
     *       channel) float to the front and keep their existing relative order. Because the
     *       caller's sort is stable and orphans all compare equal, their order is untouched.
     *   <li>Event channels follow, ordered by the event's instant — so two events on the same
     *       day sort by time, and a December event correctly precedes the following January.
     *   <li>A private channel sorts immediately after its main event channel, even when other
     *       events share the same timestamp, by grouping on the main channel id.
     * </ul>
     *
     * @param eventTimeByChannel main event channel id → event start instant
     * @param privateToMainChannel private channel id → its main event channel id
     */
    public static Comparator<GuildChannel> byEventDate(
            Map<Long, ZonedDateTime> eventTimeByChannel, Map<Long, Long> privateToMainChannel) {
        Function<GuildChannel, ZonedDateTime> effectiveTime = channel -> {
            long id = channel.getIdLong();
            ZonedDateTime own = eventTimeByChannel.get(id);
            if (own != null) {
                return own;
            }
            Long main = privateToMainChannel.get(id);
            return main == null ? null : eventTimeByChannel.get(main);
        };
        Function<GuildChannel, Long> groupId = channel -> {
            long id = channel.getIdLong();
            return eventTimeByChannel.containsKey(id) ? Long.valueOf(id) : privateToMainChannel.get(id);
        };
        Function<GuildChannel, Boolean> isPrivate = channel -> privateToMainChannel.containsKey(channel.getIdLong());

        return Comparator.comparing(effectiveTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(groupId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(isPrivate);
    }

    /**
     * Order the archive category by the {@code dd-MMM} prefix parsed from each channel name.
     * Channels that don't follow the naming convention sort to the end rather than breaking
     * the sort.
     */
    public static Comparator<GuildChannel> byChannelName() {
        DateTimeFormatter monthParser = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(Locale.ENGLISH);
        return Comparator.comparing(
                channel -> DiscordUtil.getMonthDayFromChannelName(channel, monthParser),
                Comparator.nullsLast(Comparator.<MonthDay>naturalOrder()));
    }
}
