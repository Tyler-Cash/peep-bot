package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.GoogleCalendarService;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.Deletable;
import org.javacord.api.entity.channel.*;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.component.ActionRow;
import org.javacord.api.entity.message.component.Button;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.exception.NotFoundException;
import org.javacord.api.interaction.MessageComponentInteraction;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.awt.*;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.DiscordUtil.generateAttendanceTitle;


@Log4j2
@Service
@AllArgsConstructor
public class DiscordService {
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String MAYBE = "maybe";
    private final DiscordConfiguration discordConfiguration;
    private final DiscordApi discordApi;
    private final EventRepository eventRepository;
    private final GoogleCalendarService googleCalendarService;

    private static void flipAttendeesState(Set<Attendee> attendees, String id, String username) {
        Attendee attendee = Attendee.createDiscordAttendee(id, username);
        if (!attendees.contains(attendee)) {
            attendees.add(attendee);
        } else {
            attendees.remove(attendee);
        }
    }

    private static MonthDay getMonthDayFromChannelName(ServerChannel channel, DateTimeFormatter monthParser) {
        String[] split = channel.getName().replaceAll("(?<=\\d)(st|nd|rd|th)", "").split("-");
        String day = split[0];
        int month = monthParser.parse(split[1]).get(ChronoField.MONTH_OF_YEAR);
        return MonthDay.of(month, Integer.parseInt(day));
    }

    @PostConstruct
    public void setupListeners() {
        this.createMessageComponentListener();
    }

    private MessageBuilder createEventMessage(Event event) {
        MessageBuilder builder = new MessageBuilder();
        builder
                .setEmbed(getEmbed(event))
                .addComponents(ActionRow.of(
                        Button.secondary(ACCEPTED, ACCEPTED_EMOJI),
                        Button.secondary(DECLINED, DECLINED_EMOJI),
                        Button.secondary(MAYBE, MAYBE_EMOJI)));

        return builder;
    }

    private EmbedBuilder getEmbed(Event event) {
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

        embed.addField("Links", "[Add to Google calendar](" + googleCalendarService.getCalendarEventUrl(event) + ")");

        Optional<Server> server = discordApi.getServerById(event.getServerId());
        populateAttendeeSection(event, server, embed);
        return embed;
    }

    private void populateAttendeeSection(Event event, Optional<Server> server, EmbedBuilder embed) {
        if (server.isEmpty()) {
            embed.addField("No attendees yet", "");
        } else {
            embedAttendees(server.get(), event, embed);
        }
    }

    private void embedAttendees(Server server, Event event, EmbedBuilder embed) {
        embed.addInlineField(generateAttendanceTitle(ACCEPTED_EMOJI + " Accepted", event.getAccepted().size(), event.getCapacity()), reduceAttendeesToBlock(server, event.getAccepted()))
                .addInlineField(generateAttendanceTitle(DECLINED_EMOJI + " Declined", event.getDeclined().size(), 0), reduceAttendeesToBlock(server, event.getDeclined()))
                .addInlineField(generateAttendanceTitle(MAYBE_EMOJI + " Maybe", event.getMaybe().size(), 0), reduceAttendeesToBlock(server, event.getMaybe()));
    }

    private String reduceAttendeesToBlock(Server server, Set<Attendee> attendees) {
        Set<Attendee> sortedAttendees = new TreeSet<>(Comparator.comparing(Attendee::getInstant));
        sortedAttendees.addAll(attendees);
        Set<String> names = new LinkedHashSet<>();
        sortedAttendees.forEach(attendee -> {
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

    public ServerTextChannel createEventChannel(Event event) {
        ChannelCategory category = getEventCategory();
        return category.getServer().createTextChannelBuilder()
                .setName(DiscordUtil.getChannelNameFromEvent(event))
                .setCategory(category)
                .create()
                .join();
    }

    private ChannelCategory getArchiveCategory() {
        return getChannelCategory(EVENT_ARCHIVE_CATEGORY);
    }

    @NotNull
    private ChannelCategory getChannelCategory(String categoryName) {
        Set<ChannelCategory> channels = discordApi.getChannelCategoriesByName(categoryName);
        if (channels.size() > 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Found multiple matching channels");
        } else if (channels.stream().findFirst().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + EVENT_CATEGORY + "\"");
        }
        return channels.stream().findFirst().get();
    }

    public ChannelCategory getEventCategory() {
        return getChannelCategory(EVENT_CATEGORY);
    }

    public Message postEventMessage(Event event, ServerTextChannel channel) {
        MessageBuilder builder = createEventMessage(event);
        return builder
                .send(channel)
                .join();
    }

    public Message updateEventMessage(Event event) {
        Optional<Channel> optionalChannel = discordApi.getChannelById(event.getChannelId());
        if (optionalChannel.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No discord channel found with ID " + event.getChannelId());
        }
        Message message = ((TextChannel) optionalChannel.get()).getMessageById(event.getMessageId()).join();
        return message.edit(getEmbed(event)).join();
    }

    public void pinMessage(Message message) {
        message.pin().join();
    }

    public boolean isUserMemberOfServer(long serverId, long userId) {
        return getServerById(serverId).getMembers().stream().anyMatch(member -> member.getId() == userId);
    }

    private Server getServerById(long serverId) {
        Optional<Server> server = discordApi.getServerById(serverId);
        if (server.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No discord server found with ID " + serverId);
        }
        return server.get();
    }

    @Async
    public void sortChannels(ChannelCategory eventCategory) {
        List<RegularServerChannel> channels = eventCategory.getChannels();
        DateTimeFormatter monthParser = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(Locale.ENGLISH);
        Stream<RegularServerChannel> events = channels.stream();
        int position = 0;
        for (RegularServerChannel channel : channels) {
            events = events.skip(1);
            position++;
            if (channel.getName().equalsIgnoreCase(discordConfiguration.getSeperatorChannel())) {
                break;
            }
        }

        List<RegularServerChannel> sorted = events
                .sorted((RegularServerChannel left, RegularServerChannel right) -> {
                    MonthDay leftDay = getMonthDayFromChannelName(left, monthParser);
                    MonthDay rightDay = getMonthDayFromChannelName(right, monthParser);
                    return leftDay.compareTo(rightDay);
                })
                .toList();
        for (RegularServerChannel regularServerChannel : sorted) {
            regularServerChannel.updateRawPosition(++position);
        }
    }

    public void deleteEventChannel(Event event) {
        getServerById(event.getServerId()).getChannels()
                .stream()
                .filter(channel -> channel.getId() == event.getChannelId())
                .findFirst()
                .ifPresentOrElse(
                        Deletable::delete,
                        () -> {
                            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No discord channel found with ID " + event.getChannelId());
                        });
    }

    public void archiveEventChannel(Event event) {
        Optional<Channel> eventChannel = discordApi.getChannelById(event.getChannelId());
        if (eventChannel.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No channel found with ID " + event.getChannelId());
        }
        Optional<Categorizable> categorizable = eventChannel.get().asCategorizable();
        if (categorizable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Channel not cateogrizable " + event.getChannelId());
        }
        categorizable.get().updateCategory(getArchiveCategory());
    }

    public void createMessageComponentListener() {
        discordApi.addMessageComponentCreateListener(listenerEvent -> {
            MessageComponentInteraction messageComponentInteraction = listenerEvent.getMessageComponentInteraction();

            String eventType = messageComponentInteraction.getCustomId();
            Message message = messageComponentInteraction.getMessage();
            Event event = eventRepository.findByMessageId(message.getId());
            if (event == null) {
                throw new RuntimeException("Unrecognized event message ID " + message.getId());
            }
            String userId = messageComponentInteraction.getUser().getIdAsString();
            log.info("User {} interacting with status {}", messageComponentInteraction.getUser().getName(), eventType);
            Optional<Server> server = discordApi.getServerById(event.getServerId());
            if (server.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No server found with ID " + event.getServerId());
            }
            handleMessageComponentInteraction(event, messageComponentInteraction.getUser().getDisplayName(server.get()), eventType, userId);

            message.edit(getEmbed(event));
            eventRepository.save(event);
            listenerEvent.getMessageComponentInteraction().acknowledge();
        });
    }

    @SneakyThrows
    private void handleMessageComponentInteraction(Event event, String userDisplayName, String eventType, String userId) {
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
}
