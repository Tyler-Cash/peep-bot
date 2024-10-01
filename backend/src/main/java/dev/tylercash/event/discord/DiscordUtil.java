package dev.tylercash.event.discord;

import com.ibm.icu.text.RuleBasedNumberFormat;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jetbrains.annotations.NotNull;

import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static dev.tylercash.event.discord.listener.ButtonInteractionListener.*;

@NoArgsConstructor
public class DiscordUtil {
    public static final String CHANNEL_SEPERATOR = "-";
    public static final DateTimeFormatter CHANNEL_NAME_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM");
    private static final RuleBasedNumberFormat RULE_BASED_NUMBER_FORMAT = new RuleBasedNumberFormat(Locale.UK, RuleBasedNumberFormat.ORDINAL);

    public static String getChannelNameFromEvent(Event event) {
        String day = RULE_BASED_NUMBER_FORMAT.format(event.getDateTime().withZoneSameInstant(ZoneId.of("Australia/Sydney")).getDayOfMonth());
        String month = event.getDateTime()
                .getMonth()
                .getDisplayName(TextStyle.SHORT, Locale.US);
        String name = event.getName().replaceAll("/\\s+/g", "-").toLowerCase(Locale.ROOT);
        return (day + CHANNEL_SEPERATOR + month + CHANNEL_SEPERATOR + name)
                .toLowerCase(Locale.ROOT);
    }

    public static String generateAttendanceTitle(String attendanceGroup, int count, int capacity) {
        String attendanceModifier = capacity > 0 ?
                " (Capacity " + count + "/" + capacity + ")" :
                " (" + count + ")";
        return attendanceGroup + attendanceModifier;
    }


    @SneakyThrows
    public static void handleMessageComponentInteraction(Event event, Member member, String eventType) {
        String userId = member.getId();
        String userDisplayName = getUserDisplayName(member);
        switch (eventType) {
            case ACCEPTED:
                flipAttendeesState(event.getAccepted(), userId, userDisplayName);
                event.getDeclined().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                event.getMaybe().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                break;
            case DECLINED:
                flipAttendeesState(event.getDeclined(), userId, userDisplayName);
                event.getAccepted().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                event.getMaybe().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                break;
            case MAYBE:
                flipAttendeesState(event.getMaybe(), userId, userDisplayName);
                event.getAccepted().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                event.getDeclined().remove(Attendee.createDiscordAttendee(userId, userDisplayName));
                break;
            default:
                break;
        }
    }

    @NotNull
    public static String getUserDisplayName(Member member) {
        return MarkdownSanitizer.escape(
                Optional.ofNullable(member.getNickname())
                        .orElse(member.getEffectiveName()),
                true);
    }

    private static void flipAttendeesState(Set<Attendee> attendees, String id, String username) {
        Attendee attendee = Attendee.createDiscordAttendee(id, username);
        if (!attendees.contains(attendee)) {
            attendees.add(attendee);
        } else {
            attendees.remove(attendee);
        }
    }


    public static MonthDay getMonthDayFromChannelName(TextChannel channel, DateTimeFormatter monthParser) {
        String[] split = channel.getName().replaceAll("(?<=\\d)(st|nd|rd|th)", "").split("-");
        String day = split[0];
        int month = monthParser.parse(split[1]).get(ChronoField.MONTH_OF_YEAR);
        return MonthDay.of(month, Integer.parseInt(day));
    }
}
