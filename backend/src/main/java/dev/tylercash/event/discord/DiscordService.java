package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
    private final EmbedService embedService;
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
        List<Role> rolesToMention = getRoles(channel.getGuild().getIdLong(), discordConfiguration.getEventsRole());
        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addEmbeds(embedService.getMessage(event, clock))
                .addActionRow(List.of(
                        Button.secondary(ACCEPTED, ACCEPTED_EMOJI),
                        Button.secondary(DECLINED, DECLINED_EMOJI),
                        Button.secondary(MAYBE, MAYBE_EMOJI),
                        Button.secondary(PLUS_ONE_ID, PLUS_ONE))
                );
        messageBuilder.addContent(event.getName() + " created\n");
        if (event.isNotifyOnCreate()) {
            addNotificationToMessage(messageBuilder, rolesToMention);
        }
        MessageCreateAction messageCreateAction = channel.sendMessage(messageBuilder.build());
        Message message = messageCreateAction.complete();
        message.pin().queue();
        return message;
    }

    private void addNotificationToMessage(MessageCreateBuilder messageBuilder, List<Role> rolesToMention) {
        if (notifyEventRoles.acquirePermission()) {
            try {
                rolesToMention.forEach(role -> messageBuilder.mentionRoles(role.getId())
                        .addContent(role.getAsMention())
                        .addContent("\n")
                );
                notifyEventRoles.onSuccess();
            } catch (Exception e) {
                notifyEventRoles.onError(e);
                throw e;
            }
        } else {
            messageBuilder.addContent("Not notifying @events because we've exceeded ping quota.\n");
        }
    }

    public void updateEventMessage(Event event) {
        getChannel(event)
                .editMessageEmbedsById(event.getMessageId(), embedService.getMessage(event, clock))
                .queue();
    }

    public Member getMemberFromServer(long serverId, long userId) {
        Guild server = jda.getGuildById(serverId);
        server.retrieveMemberById(userId).complete();
        return server.retrieveMemberById(userId).complete();
    }

    public boolean isUserMemberOfServer(long serverId, long userId) {
        return getMemberFromServer(serverId, userId) != null;
    }

    public boolean isUserAdminOfServer(long serverId, long userId) {
        Member member = getMemberFromServer(serverId, userId);
        return member != null && member.getRoles().stream()
                .anyMatch(role -> role.getName().equalsIgnoreCase(discordConfiguration.getAdminRole()));
    }

    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortActiveChannels() {
        sortChannels(getEventCategory(discordConfiguration.getGuildId()), discordConfiguration.getSeperatorChannel());
    }

    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortArchiveChannels() {
        sortChannels(getArchiveCategory(discordConfiguration.getGuildId()), null);
    }

    public void sortChannels(Category eventCategory, String separator) {
        List<TextChannel> channels = eventCategory.getTextChannels();

        DateTimeFormatter monthParser = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(Locale.ENGLISH);

        int separatorIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getName().equalsIgnoreCase(separator)) {
                separatorIndex = i;
                break;
            }
        }

        List<TextChannel> before;
        List<TextChannel> after;
        if (separatorIndex != -1) {
            before = channels.subList(0, separatorIndex + 1);
            after = channels.subList(separatorIndex + 1, channels.size());
        } else {
            before = List.of();
            after = channels;
        }

        List<TextChannel> sortedAfter = after.stream()
                .sorted(Comparator.comparing(channel -> getMonthDayFromChannelName(channel, monthParser)))
                .toList();

        List<TextChannel> sorted = new ArrayList<>(before);
        sorted.addAll(sortedAfter);

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
        sortChannels(category, null);
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
                    .map(user -> getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(user.getSnowflake())))
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

    public void sendMaybeConfirmationMessage(Event event) {
        if (event.getNotifications().contains(new Notification(NotificationType.MAYBE_CONFIRMATION))) {
            return;
        }

        Guild server = jda.getGuildById(discordConfiguration.getGuildId());
        Role dontNotifyRole = getOrCreateDontNotifyRole(server);

        List<Member> maybesToNotify = event.getMaybe().stream()
                .filter(user -> !user.getSnowflake().isBlank())
                .map(user -> getMemberFromServer(server.getIdLong(), Long.parseLong(user.getSnowflake())))
                .filter(member -> member.getRoles().stream().noneMatch(role -> role.equals(dontNotifyRole)))
                .toList();

        if (maybesToNotify.isEmpty()) {
            return;
        }

        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addContent("Please confirm if you're going to **" + event.getName() + "**. ");

        maybesToNotify.forEach(member -> messageBuilder.addContent(member.getAsMention() + " "));

        messageBuilder.addContent("\n\nReact " + DECLINED_EMOJI + " if you don't want confirmations in the future");

        TextChannel channel = getChannel(event);
        Message message = channel.sendMessage(messageBuilder.build()).complete();
        message.addReaction(Emoji.fromUnicode(DECLINED_EMOJI)).queue();

        event.setMaybeConfirmationMessageId(message.getIdLong());
        event.getNotifications().add(new Notification(NotificationType.MAYBE_CONFIRMATION, ZonedDateTime.now(clock).toInstant(), message.getIdLong()));
    }

    private Role getOrCreateDontNotifyRole(Guild guild) {
        List<Role> roles = guild.getRolesByName(discordConfiguration.getDontNotifyMaybeRole(), true);
        if (roles.isEmpty()) {
            return guild.createRole()
                    .setName(discordConfiguration.getDontNotifyMaybeRole())
                    .complete();
        }
        return roles.get(0);
    }
}
