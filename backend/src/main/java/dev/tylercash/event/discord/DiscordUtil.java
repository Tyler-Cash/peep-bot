package dev.tylercash.event.discord;

import com.ibm.icu.text.RuleBasedNumberFormat;
import dev.tylercash.event.event.model.Event;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

@NoArgsConstructor
public class DiscordUtil {
    public static final String CHANNEL_SEPERATOR = "-";
    public static final DateTimeFormatter CHANNEL_NAME_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM");
    private static final RuleBasedNumberFormat RULE_BASED_NUMBER_FORMAT = new RuleBasedNumberFormat(Locale.UK, RuleBasedNumberFormat.ORDINAL);

    public static String getChannelNameFromEvent(Event event) {
        String day = RULE_BASED_NUMBER_FORMAT.format(event.getDateTime().getDayOfMonth());
        String month = event.getDateTime()
                .getMonth()
                .getDisplayName(TextStyle.SHORT, Locale.US);
        String name = event.getName().replaceAll("/\\s+/g", "-").toLowerCase(Locale.ROOT);
        String nameMinified = name.substring(0, Math.min(20, name.length()));
        return (day + CHANNEL_SEPERATOR + month + CHANNEL_SEPERATOR + nameMinified)
                .toLowerCase(Locale.ROOT);
    }

    public static String generateAttendanceTitle(String attendanceGroup, int count, int capacity) {
        String attendanceModifier = capacity > 0 ?
                " (Capacity " + count + "/" + capacity + ")" :
                " (" + count + ")";
        return attendanceGroup + attendanceModifier;

    }
}
