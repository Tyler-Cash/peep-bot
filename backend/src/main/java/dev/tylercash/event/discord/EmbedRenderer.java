package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.GoogleCalendarService;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.NotFoundException;

import java.awt.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.DiscordUtil.generateAttendanceTitle;

@Log4j2
@AllArgsConstructor
public class EmbedRenderer {
    private final DiscordApi discordApi;
    private final Event event;
    private final Server server;
    private final Clock clock;

    public EmbedBuilder getEmbedBuilder() {
        long epochSecond = event.getDateTime().toEpochSecond(ZoneOffset.UTC);
        String timeMessage = "<t:" + epochSecond + ":F>\n<t:" + epochSecond + ":R>";
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(event.getName())
                .setDescription(event.getDescription())
                .addField("Time", timeMessage)
                .setColor(Color.orange);

        if (!event.getLocation().isBlank()) {
            embed.addField("Location", event.getLocation());
        }

        embed.addField("Links", "[Add to Google calendar](" + GoogleCalendarService.getCalendarEventUrl(event) + ")");
        populateAttendeeSection(event, embed);
        embed.setFooter("Last updated: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now(clock)));
        return embed;
    }

    private void populateAttendeeSection(Event event, EmbedBuilder embed) {
        if (event.getAccepted().isEmpty() && event.getDeclined().isEmpty() && event.getMaybe().isEmpty()) {
            embed.addField("No attendees yet", "");
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

        embed.addInlineField(generateAttendanceTitle(ACCEPTED_EMOJI + " Accepted", accepted.size(), event.getCapacity()), reduceAttendeesToBlock(server, accepted))
                .addInlineField(generateAttendanceTitle(DECLINED_EMOJI + " Declined", event.getDeclined().size(), 0), reduceAttendeesToBlock(server, event.getDeclined()))
                .addInlineField(generateAttendanceTitle(MAYBE_EMOJI + " Maybe", event.getMaybe().size(), 0), reduceAttendeesToBlock(server, event.getMaybe()));
        if (!waitlist.isEmpty()) {
            embed.addField(generateAttendanceTitle("Waitlist", waitlist.size(), 0), reduceAttendeesToBlock(server, waitlist));
        }
    }

    private String reduceAttendeesToBlock(Server server, Set<Attendee> attendees) {
        Set<String> names = new LinkedHashSet<>();
        attendees.forEach(attendee -> {
            String name = attendee.getName();
            if (Objects.nonNull(attendee.getSnowflake()) && Objects.isNull(attendee.getName())) {
                try {
                    name = discordApi.getUserById(attendee.getSnowflake()).join().getDisplayName(server);
                } catch (CompletionException e) {
                    if (e.getCause().getClass().equals(NotFoundException.class)) {
                        log.warn("User with id " + attendee.getSnowflake() + " not found");
                    }
                }
                attendee.setName(name);
            }
            names.add(name);
        });
        return names.stream()
                .map(attendee -> "> " + attendee + "\n")
                .reduce("", String::concat);
    }

}
