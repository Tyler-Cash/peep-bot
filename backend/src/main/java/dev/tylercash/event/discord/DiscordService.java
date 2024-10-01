package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.DiscordUtil.getMonthDayFromChannelName;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.*;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static java.util.concurrent.TimeUnit.MINUTES;

@Log4j2
@Service
@AllArgsConstructor
public class DiscordService {

    private final DiscordConfiguration discordConfiguration;
    private final RateLimiter notifyEventRoles;
    private final Clock clock;
    private final JDA jda;

    public TextChannel createEventChannel(Event event) {
        Category category = getEventCategory(discordConfiguration.getGuildId());
        ChannelAction<TextChannel> textChannelChannelAction = category.createTextChannel(DiscordUtil.getChannelNameFromEvent(event))
                .setPosition(99);
        return textChannelChannelAction.complete();
    }


    public void updateChannelName(Event event) {
        TextChannel channel = getChannel(event);
        if (!DiscordUtil.getChannelNameFromEvent(event).equals(channel.getName())) {
            channel.getManager()
                    .setName(DiscordUtil.getChannelNameFromEvent(event))
                    .queue();
        }
    }

    private Category getArchiveCategory(long serverId) {
        return getChannelCategory(serverId, EVENT_ARCHIVE_CATEGORY);
    }

    @NotNull
    private Category getChannelCategory(long serverId, String categoryName) {
        List<Category> categories = jda.getGuildById(serverId).getCategoriesByName(categoryName, true);
        if (categories.size() > 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Found multiple matching channels");
        } else if (categories.stream().findFirst().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + EVENT_CATEGORY + "\"");
        }
        return categories.get(0);
    }

    public @NotNull Category getEventCategory(long serverId) {
        return getChannelCategory(serverId, EVENT_CATEGORY);
    }

    public Message postEventMessage(Event event, TextChannel channel) {
        MessageEmbed messageEmbed = new EmbedRenderer(event, clock).getEmbedBuilder().build();
        List<Role> rolesToMention = getRoles(channel.getGuild().getIdLong(), discordConfiguration.getEventsRole());
        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addEmbeds(messageEmbed)
                .addActionRow(List.of(
                        Button.secondary(ACCEPTED, ACCEPTED_EMOJI),
                        Button.secondary(DECLINED, DECLINED_EMOJI),
                        Button.secondary(MAYBE, MAYBE_EMOJI),
                        Button.secondary(PLUS_ONE_ID, PLUS_ONE))
                );
        messageBuilder.addContent(event.getName() + " created\n");
        notifyEventRoles.executeRunnable(() -> rolesToMention.forEach(role -> messageBuilder.mentionRoles(role.getId())
                .addContent(role.getAsMention())));
        MessageCreateAction messageCreateAction = channel.sendMessage(messageBuilder.build());
        Message message = messageCreateAction.complete();
        message.pin().queue();
        return message;
    }

    public void updateEventMessage(Event event) {
        getChannel(event)
                .editMessageEmbedsById(event.getMessageId(), new EmbedRenderer(event, clock).getEmbedBuilder().build())
                .queue();
    }

    public boolean isUserMemberOfServer(long serverId, long userId) {
        Guild server = jda.getGuildById(serverId);
        server.retrieveMemberById(userId).complete();
        return server.retrieveMemberById(userId).complete() != null;
    }

    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortChannels() {
        sortChannels(getEventCategory(discordConfiguration.getGuildId()));
    }

    public void sortChannels(Category eventCategory) {
        List<TextChannel> channels = eventCategory.getTextChannels();
        DateTimeFormatter monthParser = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(Locale.ENGLISH);
        Stream<TextChannel> events = channels.stream();
        int position = 0;
        for (TextChannel channel : channels) {
            events = events.skip(1);
            position++;
            if (channel.getName().equalsIgnoreCase(discordConfiguration.getSeperatorChannel())) {
                break;
            }
        }

        List<TextChannel> sorted = Stream.concat(
                channels.subList(0, position).stream(),
                events.sorted((TextChannel left, TextChannel right) -> {
                    MonthDay leftDay = getMonthDayFromChannelName(left, monthParser);
                    MonthDay rightDay = getMonthDayFromChannelName(right, monthParser);
                    return leftDay.compareTo(rightDay);
                })
        ).toList();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).getManager().setPosition(i).queue();
        }
    }

    public void deleteEventChannel(Event event) {
        jda.getGuildById(discordConfiguration.getGuildId())
                .getChannelById(TextChannel.class, event.getChannelId())
                .delete()
                .queue();
    }

    public void archiveEventChannel(Event event) {
        TextChannel eventChannel = getChannel(event);
        Category category = getArchiveCategory(discordConfiguration.getGuildId());
        eventChannel.getManager()
                .setParent(category)
                .sync()
                .queue();
    }

    private TextChannel getChannel(Event event) {
        return jda.getChannelById(TextChannel.class, event.getChannelId());
    }

    private List<Role> getRoles(long serverId, String role) {
        List<Role> rolesByName = jda.getGuildById(serverId).getRolesByName(role, true);
        if (rolesByName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + role);
        }
        return rolesByName;
    }

    public void sendMessageBeforeEvent(Event event) {
        if (event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT))) {
            return;
        }
        notifyEventRoles.executeRunnable(() -> {
            Guild server = jda.getGuildById(discordConfiguration.getGuildId());
            MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                    .addContent("`" + event.getName() + "`  starting in " +
                            "<t:" + event.getDateTime().toEpochSecond() + ":R>\n"
                    );
            event.getAccepted().stream()
                    .filter(user -> !user.getSnowflake().isBlank())
                    .map(user -> jda.getGuildById(discordConfiguration.getGuildId()).getMember(UserSnowflake.fromId(user.getSnowflake())))
                    .filter(user -> user.getRoles().stream().anyMatch(role -> role.getName().equals("pre-event-notification")))
                    .forEach(user -> messageBuilder.addContent(user.getAsMention() + " "));
            log.info("Sending {} alert for \"{}\"", NotificationType.START_OF_EVENT, event.getName());

            event.getNotifications().add(
                    new Notification(
                            NotificationType.START_OF_EVENT,
                            ZonedDateTime.now(clock).toInstant(),
                            server.getChannelById(TextChannel.class, event.getChannelId()).sendMessage(
                                    messageBuilder
                                            .build()
                            ).complete().getIdLong()
                    ));
        });
    }
}
