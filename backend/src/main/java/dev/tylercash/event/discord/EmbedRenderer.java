package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.GoogleCalendarService;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.DiscordUtil.generateAttendanceTitle;

@Log4j2
@AllArgsConstructor
public class EmbedRenderer {
    private final Event event;
    private final Clock clock;

    public EmbedBuilder getEmbedBuilder() {
        long epochSecond = event.getDateTime().toEpochSecond();
        String timeMessage = "<t:" + epochSecond + ":F>\n<t:" + epochSecond + ":R>";
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(event.getName())
                .setDescription(event.getDescription())
                .addField("Time", timeMessage, false)
                .setColor(Color.YELLOW);

        if (!event.getLocation().isBlank()) {
            embed.addField("Location", event.getLocation(), false);
        }

        embed.addField("Links", "[Add to Google calendar](" + GoogleCalendarService.getCalendarEventUrl(event) + ")", false);
        populateAttendeeSection(event, embed);
        embed.setFooter("Last updated: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now(clock)));
        return embed;
    }

    private void populateAttendeeSection(Event event, EmbedBuilder embed) {
        if (event.getAccepted().isEmpty() && event.getDeclined().isEmpty() && event.getMaybe().isEmpty()) {
            embed.addField("No attendees yet", "", false);
        } else {
            embedAttendees(embed);
        }
    }

    private void embedAttendees(EmbedBuilder embed) {
        List<Attendee> sortedAccepted = event.getAccepted().stream()
                .sorted(Comparator.comparing(Attendee::getInstant))
                .toList();
        int eventCapacity = event.getCapacity() == 0 ? sortedAccepted.size() : event.getCapacity();
        Set<Attendee> accepted = sortedAccepted.stream()
                .limit(eventCapacity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Attendee> waitlist = sortedAccepted.stream()
                .skip(eventCapacity)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        embed.addField(
                generateAttendanceTitle(ACCEPTED_EMOJI + " Accepted", accepted.size(), event.getCapacity()),
                reduceAttendeesToBlock(accepted), true);
        embed.addField(
                generateAttendanceTitle(DECLINED_EMOJI + " Declined", event.getDeclined().size(), 0),
                reduceAttendeesToBlock(event.getDeclined()), true);
        embed.addField(
                generateAttendanceTitle(MAYBE_EMOJI + " Maybe", event.getMaybe().size(), 0),
                reduceAttendeesToBlock(event.getMaybe()), true);
        if (!waitlist.isEmpty()) {
            embed.addField(
                    generateAttendanceTitle("Waitlist", waitlist.size(), 0),
                    reduceAttendeesToBlock(waitlist), false);
        }
    }

    private String reduceAttendeesToBlock(Set<Attendee> attendees) {
        Set<String> names = new LinkedHashSet<>();
        attendees.forEach(attendee -> names.add(attendee.getName()));
        return names.stream()
                .map(attendee -> "> " + attendee + "\n")
                .reduce("", String::concat);
    }
}
