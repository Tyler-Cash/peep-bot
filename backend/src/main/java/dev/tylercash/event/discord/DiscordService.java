package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.global.GoogleCalendarService;
import dev.tylercash.event.global.MetricsService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.Deletable;
import org.javacord.api.entity.channel.*;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.component.ActionRow;
import org.javacord.api.entity.message.component.Button;
import org.javacord.api.entity.message.component.TextInput;
import org.javacord.api.entity.message.component.TextInputStyle;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.message.mention.AllowedMentions;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.interaction.MessageComponentInteraction;
import org.javacord.api.interaction.ModalInteraction;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static java.util.concurrent.TimeUnit.HOURS;
import static org.javacord.api.entity.message.TimestampStyle.RELATIVE_TIME;


@Log4j2
@Service
@RequiredArgsConstructor()
public class DiscordService {
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String MAYBE = "maybe";
    public static final String PLUS_ONE = "Add +1";
    private final DiscordConfiguration discordConfiguration;
    private final DiscordApi discordApi;
    private final EventRepository eventRepository;
    private final GoogleCalendarService googleCalendarService;
    private final MetricsService metricsService;
    private final Clock clock;


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

    protected static MessageBuilder getMessageBuilderMentioningRoles(String message, Set<Role> roles) {
        AllowedMentions allowedMentions = getAllowedMentions(roles);
        MessageBuilder messageBuilder = new MessageBuilder()
                .setAllowedMentions(allowedMentions);
        roles.forEach(role -> messageBuilder.append(role.getMentionTag()));
        messageBuilder.append(message);
        return messageBuilder;
    }

    protected static AllowedMentions getAllowedMentions(Set<Role> rolesByName) {
        AllowedMentionsBuilder allowedMentionsBuilder = new AllowedMentionsBuilder()
                .setMentionRoles(true);
        rolesByName.forEach(role -> allowedMentionsBuilder.addRole(role.getId()));
        return allowedMentionsBuilder.build();
    }

    @PostConstruct
    public void setupListeners() {
        this.createListeners();
    }

    private MessageBuilder createEventMessage(Event event) {
        MessageBuilder builder = new MessageBuilder();
        builder
                .setEmbed(getEmbed(event))
                .addComponents(ActionRow.of(
                        Button.secondary(ACCEPTED, ACCEPTED_EMOJI),
                        Button.secondary(DECLINED, DECLINED_EMOJI),
                        Button.secondary(MAYBE, MAYBE_EMOJI),
                        Button.secondary("plus1", PLUS_ONE)
                ));
        return builder;
    }

    private EmbedBuilder getEmbed(Event event) {
        Optional<Server> server = discordApi.getServerById(event.getServerId() != 0L ? event.getServerId() : discordConfiguration.getGuildId());
        if (server.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Server not found");
        }
        EmbedRenderer renderrer = new EmbedRenderer(discordApi, event, server.get(), clock);
        return renderrer.getEmbedBuilder();
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
        Message message = ((TextChannel) getChannel(event)).getMessageById(event.getMessageId()).join();
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

    @Scheduled(fixedDelay = 6, timeUnit = HOURS)
    public void sortChannels() {
        sortChannels(getEventCategory());
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

        List<RegularServerChannel> sorted = Stream.concat(
                channels.subList(0, position).stream(),
                events.sorted((RegularServerChannel left, RegularServerChannel right) -> {
                    MonthDay leftDay = getMonthDayFromChannelName(left, monthParser);
                    MonthDay rightDay = getMonthDayFromChannelName(right, monthParser);
                    return leftDay.compareTo(rightDay);
                })
        ).toList();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).updateRawPosition(i);
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
        Channel eventChannel = getChannel(event);
        Optional<Categorizable> categorizable = eventChannel.asCategorizable();
        if (categorizable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Channel not cateogrizable " + event.getChannelId());
        }
        categorizable.get().updateCategory(getArchiveCategory());
    }

    private Channel getChannel(Event event) {
        Optional<Channel> eventChannel = discordApi.getChannelById(event.getChannelId());
        if (eventChannel.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No channel found with ID " + event.getChannelId());
        }
        return eventChannel.get();
    }

    public void createListeners() {
        discordApi.addModalSubmitListener(listenerEvent -> {
            long startTime = System.nanoTime();
            ModalInteraction interaction = listenerEvent.getModalInteraction();
            Event event = eventRepository.findByChannelId(interaction.getChannel().get().getId());
            String plus1Name = interaction.getTextInputValues().get(0);
            event.getAccepted().add(Attendee.createDiscordAttendee(null, plus1Name));
            Message message = listenerEvent.getModalInteraction().getChannel().get().getMessageById(event.getMessageId()).join();
            updateMessage(message, event);
            listenerEvent.getModalInteraction().respondLater(true).join().delete();
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;
            recordUserInteraction(interaction.getUser(), "plus1", event, duration);
        });
        discordApi.addMessageComponentCreateListener(listenerEvent -> {
            long startTime = System.nanoTime();
            MessageComponentInteraction messageComponentInteraction = listenerEvent.getMessageComponentInteraction();
            if (messageComponentInteraction.getCustomId().equals("plus1")) {
                listenerEvent.getInteraction().respondWithModal("plus1", PLUS_ONE,
                        ActionRow.of(TextInput.create(TextInputStyle.SHORT, "plus1", "Enter name of +1")));
                return;
            }
            String eventType = messageComponentInteraction.getCustomId();
            Message message = messageComponentInteraction.getMessage();
            Event event = eventRepository.findByMessageId(message.getId());
            if (event == null) {
                log.warn("Unrecognized event message ID " + message.getId());
                return;
            }
            String userId = messageComponentInteraction.getUser().getIdAsString();
            Optional<Server> server = discordApi.getServerById(event.getServerId());
            if (server.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No server found with ID " + event.getServerId());
            }
            handleMessageComponentInteraction(event, messageComponentInteraction.getUser().getDisplayName(server.get()), eventType, userId);

            updateMessage(message, event);
            listenerEvent.getMessageComponentInteraction().acknowledge();
            long endTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;
            recordUserInteraction(messageComponentInteraction.getUser(), eventType, event, duration);
        });
    }

    private void recordUserInteraction(User user, String type, Event event, long duration) {
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info("User {} interacting with status {} on event {}, taking {}ms", user.getName(), type, event.getName(), duration);
    }

    private void updateMessage(Message message, Event event) {
        message.edit(getEmbed(event));
        eventRepository.save(event);
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

    @SneakyThrows
    public void initialNotificationAboutEvent(Event event, ServerTextChannel channel) {
        if (event.getNotifications().contains(new Notification(NotificationType.INITIAL_ALERT))) {
            return;
        }
        Set<Role> roles = getRoles(discordConfiguration.getEventsRole());
        MessageBuilder messageBuilder = getMessageBuilderMentioningRoles(" new event has been created!", roles);
        CompletableFuture<Message> message = messageBuilder.send(channel);
        event.getNotifications().add(
                new Notification(
                        NotificationType.INITIAL_ALERT,
                        ZonedDateTime.now(clock).toInstant(),
                        message.get().getId()
                ));
    }

    @SneakyThrows
    public void notifyUsersBeforeEventStarts(Event event) {
        if (event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT))) {
            return;
        }
        AllowedMentionsBuilder allowedMentionsBuilder = new AllowedMentionsBuilder()
                .setMentionUsers(true);
        MessageBuilder messageBuilder = new MessageBuilder()
                .setAllowedMentions(allowedMentionsBuilder.build());
        event.getAccepted().stream()
                .filter(user -> !user.getSnowflake().isBlank())
                .map(user -> discordApi.getUserById(user.getSnowflake()).join())
                .forEach(user -> messageBuilder.append(user.getMentionTag() + " "));
        messageBuilder
                .appendNewLine()
                .append("`" + event.getName() + "`")
                .append(" starting in ")
                .appendTimestamp(event.getDateTime().toEpochSecond(), RELATIVE_TIME);
        log.info("Sending {} alert for {}", NotificationType.START_OF_EVENT, event.getName());
        Message message = messageBuilder.send((TextChannel) getChannel(event)).join();
        event.getNotifications().add(
                new Notification(
                        NotificationType.START_OF_EVENT,
                        ZonedDateTime.now(clock).toInstant(),
                        message.getId()
                ));
        eventRepository.save(event);
    }

    private Set<Role> getRoles(String role) {
        Set<Role> rolesByName = discordApi.getRolesByName(role);
        if (rolesByName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found for event");
        }
        return rolesByName;
    }
}
