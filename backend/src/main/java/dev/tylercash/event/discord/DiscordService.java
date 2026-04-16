package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.*;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static java.util.concurrent.TimeUnit.MINUTES;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@AllArgsConstructor
public class DiscordService {
    private static final int ORPHAN_AGE_DAYS = 7;

    private final DiscordConfiguration discordConfiguration;
    private final EmbedService embedService;
    private final EventRepository eventRepository;
    private final FeatureTogglesConfiguration featureToggles;
    private final RateLimiter notifyEventRoles;
    private final Clock clock;
    private final JDA jda;
    private final DiscordChannelService discordChannelService;
    private final DiscordMessageService discordMessageService;
    private final DiscordRoleService discordRoleService;
    private final DiscordAuthService discordAuthService;

    @Observed(name = "discord.create-channel")
    public TextChannel createEventChannel(Event event) {
        Category category = discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_CATEGORY);
        return discordChannelService.createTextChannel(category, DiscordUtil.getChannelNameFromEvent(event));
    }

    public void updateChannelName(Event event) {
        discordChannelService.setChannelName(event.getChannelId(), DiscordUtil.getChannelNameFromEvent(event));
    }

    public @NotNull Category getEventCategory(long serverId) {
        return discordChannelService.getCategoryByName(serverId, EVENT_CATEGORY);
    }

    @Observed(name = "discord.post-message")
    public Message postEventMessage(Event event, TextChannel channel) {
        List<Role> rolesToMention =
                discordRoleService.getRolesByName(channel.getGuild().getIdLong(), discordConfiguration.getEventsRole());
        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addEmbeds(embedService.getMessage(event, clock))
                .addComponents(List.of(ActionRow.of(
                        Button.secondary(
                                ACCEPTED, discordConfiguration.getEmoji().getAccepted()),
                        Button.secondary(
                                DECLINED, discordConfiguration.getEmoji().getDeclined()),
                        Button.secondary(MAYBE, discordConfiguration.getEmoji().getMaybe()),
                        Button.secondary(PLUS_ONE_ID, PLUS_ONE))));
        messageBuilder.addContent(event.getName() + " created\n");
        if (event.isNotifyOnCreate()) {
            addNotificationToMessage(messageBuilder, rolesToMention);
        }
        Message message = channel.sendMessage(messageBuilder.build()).complete();
        message.pin().queue();
        return message;
    }

    private void addNotificationToMessage(MessageCreateBuilder messageBuilder, List<Role> rolesToMention) {
        if (notifyEventRoles.acquirePermission()) {
            try {
                rolesToMention.forEach(role -> messageBuilder
                        .mentionRoles(role.getId())
                        .addContent(role.getAsMention())
                        .addContent("\n"));
                notifyEventRoles.onSuccess();
            } catch (Exception e) {
                notifyEventRoles.onError(e);
                throw e;
            }
        } else {
            messageBuilder.addContent("Not notifying @events because we've exceeded ping quota.\n");
        }
    }

    @Observed(name = "discord.update-message")
    public void updateEventMessage(Event event) {
        discordMessageService.editEmbeds(
                event.getChannelId(), event.getMessageId(), embedService.getMessage(event, clock));
    }

    public void removeEventButtons(Event event) {
        discordMessageService.editComponents(event.getChannelId(), event.getMessageId(), List.of());
    }

    public Member getMemberFromServer(long serverId, long userId) {
        return discordAuthService.getMember(serverId, userId);
    }

    public boolean isUserMemberOfServer(long serverId, long userId) {
        return discordAuthService.isMember(serverId, userId);
    }

    public boolean isUserAdminOfServer(long serverId, long userId) {
        return discordAuthService.isEventAdmin(serverId, userId);
    }

    @Observed(name = "discord.sort-active-channels")
    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortActiveChannels() {
        Category category = getEventCategory(discordConfiguration.getGuildId());
        sortChannelsByEventDate(category, discordConfiguration.getSeperatorChannel());
    }

    @Observed(name = "discord.sort-archive-channels")
    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortArchiveChannels() {
        Category category =
                discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_ARCHIVE_CATEGORY);
        sortChannelsByChannelName(category);
    }

    public void sortChannelsByEventDate(Category eventCategory, String separator) {
        List<TextChannel> channels = eventCategory.getTextChannels();

        int separatorIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getName().equalsIgnoreCase(separator)) {
                separatorIndex = i;
                break;
            }
        }

        List<TextChannel> before;
        List<TextChannel> toSort;
        if (separatorIndex != -1) {
            before = channels.subList(0, separatorIndex + 1);
            toSort = channels.subList(separatorIndex + 1, channels.size());
        } else {
            before = List.of();
            toSort = channels;
        }

        List<Long> channelIds = toSort.stream().map(TextChannel::getIdLong).toList();
        Map<Long, ZonedDateTime> channelDateMap = eventRepository.findByChannelIdIn(channelIds).stream()
                .collect(Collectors.toMap(Event::getChannelId, Event::getDateTime));
        Map<Long, Long> privateToMainChannelId = eventRepository.findByPrivateChannelIdIn(channelIds).stream()
                .filter(e -> e.getPrivateChannelId() != null)
                .collect(Collectors.toMap(Event::getPrivateChannelId, Event::getChannelId));

        List<TextChannel> mainEventChannels = new ArrayList<>();
        List<TextChannel> privateEventChannels = new ArrayList<>();
        List<TextChannel> withoutEvent = new ArrayList<>();
        for (TextChannel ch : toSort) {
            if (channelDateMap.containsKey(ch.getIdLong())) {
                mainEventChannels.add(ch);
            } else if (privateToMainChannelId.containsKey(ch.getIdLong())) {
                privateEventChannels.add(ch);
            } else {
                withoutEvent.add(ch);
            }
        }

        mainEventChannels.sort(Comparator.comparing(ch -> channelDateMap.get(ch.getIdLong())));

        if (!withoutEvent.isEmpty()) {
            log.warn(
                    "Found {} orphaned channel(s) in events category with no matching event: {}",
                    withoutEvent.size(),
                    withoutEvent.stream().map(TextChannel::getName).toList());
        }

        List<TextChannel> keptOrphans = new ArrayList<>();
        if (featureToggles.isArchiveOrphanedChannels()) {
            OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(ORPHAN_AGE_DAYS);
            Category archiveCategory =
                    discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_ARCHIVE_CATEGORY);
            for (TextChannel orphan : withoutEvent) {
                if (orphan.getTimeCreated().isBefore(cutoff)) {
                    log.info("Archiving orphaned channel '{}' (created {})", orphan.getName(), orphan.getTimeCreated());
                    orphan.getManager().setParent(archiveCategory).sync().queue();
                } else {
                    keptOrphans.add(orphan);
                }
            }
        } else {
            keptOrphans.addAll(withoutEvent);
        }

        List<TextChannel> sorted = new ArrayList<>(before);
        for (TextChannel mainCh : mainEventChannels) {
            sorted.add(mainCh);
            privateEventChannels.stream()
                    .filter(pc -> Objects.equals(privateToMainChannelId.get(pc.getIdLong()), mainCh.getIdLong()))
                    .findFirst()
                    .ifPresent(sorted::add);
        }
        sorted.addAll(keptOrphans);

        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).getManager().setPosition(i).queue();
        }
    }

    public void sortChannelsByChannelName(Category category) {
        discordChannelService.sortChannelsByChannelName(category);
    }

    @Observed(name = "discord.delete-channel")
    public void deleteEventChannel(Event event) {
        discordChannelService.deleteChannel(event.getChannelId());
    }

    @Observed(name = "discord.create-private-channel")
    public void createPrivateEventChannel(Event event) {
        if (event.getPrivateChannelId() != null) {
            return;
        }
        if (event.getAcceptedRoleId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Event roles have not been created yet — please wait and try again");
        }
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        Category category = discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_CATEGORY);
        String channelName = DiscordUtil.getChannelNameFromEvent(event) + "-private";
        TextChannel channel = discordChannelService.createPrivateTextChannel(
                category, channelName, guild.getPublicRole().getIdLong(), event.getAcceptedRoleId());
        event.setPrivateChannelId(channel.getIdLong());
        Message alert = channel.sendMessage(
                        "⚠️ **Private Channel** — This channel is for sharing private event details only. "
                                + "Please keep all discussion in the main event channel. Do not chat here.")
                .complete();
        alert.pin().complete();
        channel.getHistory().retrievePast(5).complete().stream()
                .filter(m -> m.getType() == net.dv8tion.jda.api.entities.MessageType.CHANNEL_PINNED_ADD)
                .forEach(m -> m.delete().queue());
    }

    @Observed(name = "discord.delete-private-channel")
    public void deletePrivateEventChannel(Event event) {
        if (event.getPrivateChannelId() == null) {
            return;
        }
        discordChannelService.deleteChannel(event.getPrivateChannelId());
    }

    @Observed(name = "discord.archive-channel")
    public void archiveEventChannel(Event event) {
        TextChannel eventChannel = discordChannelService.getTextChannel(event.getChannelId());
        Category archiveCategory =
                discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_ARCHIVE_CATEGORY);
        discordChannelService.moveChannelToCategory(eventChannel, archiveCategory);
        discordChannelService.sortChannelsByChannelName(archiveCategory);
    }

    public TextChannel getChannel(Event event) {
        return discordChannelService.getTextChannel(event.getChannelId());
    }

    @Observed(name = "discord.send-album-link")
    public void sendAlbumLink(Event event, String albumUrl) {
        if (event.getNotifications().contains(new Notification(NotificationType.ALBUM_LINK))) {
            return;
        }
        TextChannel channel = getChannel(event);
        Message message = channel.sendMessage("[Post photos of the event in the album!](" + albumUrl + ")")
                .complete();
        message.pin().queue();
        event.getNotifications()
                .add(new Notification(
                        NotificationType.ALBUM_LINK, ZonedDateTime.now(clock).toInstant(), message.getIdLong()));
    }

    @Observed(name = "discord.create-event-roles")
    public void createEventRoles(Event event) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        String baseName = event.getName();
        if (baseName.length() > 89) {
            baseName = baseName.substring(0, 89);
        }
        if (event.getAcceptedRoleId() == null) {
            Role accepted = discordRoleService.createRole(guild, baseName + " - Accepted");
            event.setAcceptedRoleId(accepted.getIdLong());
        }
        if (event.getDeclinedRoleId() == null) {
            Role declined = discordRoleService.createRole(guild, baseName + " - Declined");
            event.setDeclinedRoleId(declined.getIdLong());
        }
        if (event.getMaybeRoleId() == null) {
            Role maybe = discordRoleService.createRole(guild, baseName + " - Maybe");
            event.setMaybeRoleId(maybe.getIdLong());
        }
    }

    @Observed(name = "discord.delete-event-roles")
    public void deleteEventRoles(Event event) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        discordRoleService.deleteRole(guild, event.getAcceptedRoleId());
        discordRoleService.deleteRole(guild, event.getDeclinedRoleId());
        discordRoleService.deleteRole(guild, event.getMaybeRoleId());
    }

    @Observed(name = "discord.assign-event-role")
    public void assignEventRole(
            Event event, String snowflake, dev.tylercash.event.event.model.AttendanceStatus status) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        Member member = guild.retrieveMemberById(snowflake).complete();
        if (member == null) {
            return;
        }
        discordRoleService.removeRoleFromMember(guild, member, event.getAcceptedRoleId());
        discordRoleService.removeRoleFromMember(guild, member, event.getDeclinedRoleId());
        discordRoleService.removeRoleFromMember(guild, member, event.getMaybeRoleId());
        Long roleId =
                switch (status) {
                    case ACCEPTED -> event.getAcceptedRoleId();
                    case DECLINED -> event.getDeclinedRoleId();
                    case MAYBE -> event.getMaybeRoleId();
                    default -> null;
                };
        discordRoleService.addRoleToMember(guild, member, roleId);
    }

    public void removeAllEventRoles(Event event, String snowflake) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        Member member = guild.retrieveMemberById(snowflake).complete();
        if (member != null) {
            discordRoleService.removeRoleFromMember(guild, member, event.getAcceptedRoleId());
            discordRoleService.removeRoleFromMember(guild, member, event.getDeclinedRoleId());
            discordRoleService.removeRoleFromMember(guild, member, event.getMaybeRoleId());
        }
    }

    @Observed(name = "discord.send-pre-event-notification")
    public void sendMessageBeforeEvent(Event event) {
        if (event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT))) {
            return;
        }
        notifyEventRoles.executeRunnable(() -> {
            Guild server = jda.getGuildById(discordConfiguration.getGuildId());
            MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                    .addContent("**" + event.getName() + "**  starting at " + "<t:"
                            + event.getDateTime().toEpochSecond() + ":t>\n");
            event.getAccepted().stream()
                    .filter(user ->
                            user.getSnowflake() != null && !user.getSnowflake().isBlank())
                    .map(user ->
                            getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(user.getSnowflake())))
                    .filter(user -> user.getRoles().stream()
                            .anyMatch(role -> role.getName().equals("pre-event-notification")))
                    .forEach(user -> messageBuilder.addContent(user.getAsMention() + " "));
            log.info("Sending {} alert for \"{}\"", NotificationType.START_OF_EVENT, event.getName());

            event.getNotifications()
                    .add(new Notification(
                            NotificationType.START_OF_EVENT,
                            ZonedDateTime.now(clock).toInstant(),
                            server.getChannelById(TextChannel.class, event.getChannelId())
                                    .sendMessage(messageBuilder.build())
                                    .complete()
                                    .getIdLong()));
        });
    }
}
