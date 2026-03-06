package dev.tylercash.event.discord;

import com.ibm.icu.text.RuleBasedNumberFormat;
import dev.tylercash.event.event.model.Event;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;
import lombok.NoArgsConstructor;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jetbrains.annotations.NotNull;

@NoArgsConstructor
public class DiscordUtil {
    public static final String CHANNEL_SEPERATOR = "-";
    public static final DateTimeFormatter CHANNEL_NAME_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM");
    private static final RuleBasedNumberFormat RULE_BASED_NUMBER_FORMAT =
            new RuleBasedNumberFormat(Locale.UK, RuleBasedNumberFormat.ORDINAL);

    public static String getChannelNameFromEvent(Event event) {
        String day = RULE_BASED_NUMBER_FORMAT.format(event.getDateTime()
                .withZoneSameInstant(ZoneId.of("Australia/Sydney"))
                .getDayOfMonth());
        String month = event.getDateTime().getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        String name = event.getName().replaceAll("/\\s+/g", "-").toLowerCase(Locale.ROOT);
        return (day + CHANNEL_SEPERATOR + month + CHANNEL_SEPERATOR + name).toLowerCase(Locale.ROOT);
    }

    public static String generateAttendanceTitle(String attendanceGroup, int count, int capacity) {
        String attendanceModifier = capacity > 0 ? " (Capacity " + count + "/" + capacity + ")" : " (" + count + ")";
        return attendanceGroup + attendanceModifier;
    }

    @NotNull
    public static String getUserDisplayName(Member member) {
        return MarkdownSanitizer.escape(
                Optional.ofNullable(member.getNickname()).orElse(member.getEffectiveName()), true);
    }

    public static MonthDay getMonthDayFromChannelName(TextChannel channel, DateTimeFormatter monthParser) {
        String[] split =
                channel.getName().replaceAll("(?<=\\d)(st|nd|rd|th)", "").split("-");
        String day = split[0];
        int month = monthParser.parse(split[1]).get(ChronoField.MONTH_OF_YEAR);
        return MonthDay.of(month, Integer.parseInt(day));
    }
}
