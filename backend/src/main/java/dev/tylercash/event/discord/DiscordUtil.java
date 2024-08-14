package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class DiscordUtil {
    public static final String CHANNEL_SEPERATOR = "-";
    public static final DateTimeFormatter CHANNEL_NAME_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM");

    public static String getChannelNameFromEvent(Event event) {
        return dateFromEvent(event)
                + CHANNEL_SEPERATOR + event.getName().replaceAll("/\\s+/g", "-").toLowerCase(Locale.ROOT);
    }

    private static String dateFromEvent(Event event) {
        return CHANNEL_NAME_FORMATTER.format(event.getDateTime()).replace(CHANNEL_SEPERATOR, getDayNumberSuffix(event.getDateTime().getDayOfMonth()) + "-");
    }

    public static String datetimestampFromEvent(Event event) {
        return DateTimeFormatter.ofPattern("h:mma dd-MMM").format(event.getDateTime()).replace(CHANNEL_SEPERATOR, getDayNumberSuffix(event.getDateTime().getDayOfMonth()) + " of ");
    }

    private static String getDayNumberSuffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }
        switch (day % 10) {
            case 1:
                return "st";
            case 2:
                return "nd";
            case 3:
                return "rd";
            default:
                return "th";
        }
    }
}
