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
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.observation.ObservationRegistry;
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
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
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
    private final GuildEmojiResolver guildEmojiResolver;
    private final GuildRepository guildRepository;
    private final ObservationRegistry observationRegistry;

    @Observed(name = "discord.create-channel")
    public TextChannel createEventChannel(Event event) {
        Category category = resolvePlannedCategory(event.getServerId());
        return discordChannelService.createTextChannel(category, DiscordUtil.getChannelNameFromEvent(event));
    }

    public void updateChannelName(Event event) {
        discordChannelService.setChannelName(event.getChannelId(), DiscordUtil.getChannelNameFromEvent(event));
    }

    public @NotNull Category getEventCategory(long serverId) {
        return resolvePlannedCategory(serverId);
    }

    /**
     * Returns the planned-events category configured for the guild. Falls back to the legacy
     * named category ({@link DiscordConfiguration#EVENT_CATEGORY}) when no id is configured or
     * the configured id no longer resolves (e.g. category was deleted in Discord).
     */
    private @NotNull Category resolvePlannedCategory(long guildId) {
        Category byId = resolveCategoryById(guildId, dev.tylercash.event.discord.Guild::getPlannedCategoryId);
        return byId != null ? byId : discordChannelService.getCategoryByName(guildId, EVENT_CATEGORY);
    }

    private @NotNull Category resolveArchivedCategory(long guildId) {
        Category byId = resolveCategoryById(guildId, dev.tylercash.event.discord.Guild::getArchivedCategoryId);
        return byId != null ? byId : discordChannelService.getCategoryByName(guildId, EVENT_ARCHIVE_CATEGORY);
    }

    private Category resolveCategoryById(
            long guildId, java.util.function.Function<dev.tylercash.event.discord.Guild, String> selector) {
        String id = guildRepository.findById(guildId).map(selector).orElse(null);
        if (id == null) return null;
        Guild jdaGuild = jda.getGuildById(guildId);
        return jdaGuild == null ? null : jdaGuild.getCategoryById(id);
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.post-message")
    public Message postEventMessage(Event event, TextChannel channel) {
        long guildId = channel.getGuild().getIdLong();
        String eventsRoleName =
                guildRepository.findById(guildId).map(g -> g.getEventsRole()).orElse("events");
        List<Role> rolesToMention = discordRoleService.getRolesByName(guildId, eventsRoleName);
        GuildEmojiResolver.ResolvedEmoji emoji = guildEmojiResolver.forGuild(guildId);

        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addEmbeds(embedService.getMessage(event, clock))
                .addComponents(List.of(ActionRow.of(
                        Button.secondary(ACCEPTED, emoji.accepted()),
                        Button.secondary(DECLINED, emoji.declined()),
                        Button.secondary(MAYBE, emoji.maybe()),
                        Button.secondary(PLUS_ONE_ID, PLUS_ONE))));
        messageBuilder.addContent(event.getName() + " created\n");
        if (event.isNotifyOnCreate()) {
            addNotificationToMessage(messageBuilder, rolesToMention, channel.getGuild());
        }
        Message message = channel.sendMessage(messageBuilder.build()).complete();
        pinSilently(message, "event embed");
        return message;
    }

    /**
     * Pin the message, but log and continue if the bot is missing
     * {@code PIN_MESSAGES} in the channel. Pinning is a UX nicety; the event
     * still works without it.
     */
    private void pinSilently(Message message, String description) {
        JdaObservations.queue(message.pin(), "discord.message.pin-silently.queue", observationRegistry, null, t -> {
            if (t instanceof InsufficientPermissionException) {
                log.warn(
                        "Skipping pin of {} in #{}: bot lacks PIN_MESSAGES.",
                        description,
                        message.getChannel().getName());
            } else {
                log.warn("Failed to pin {}: {}", description, t.getMessage());
            }
        });
    }

    private void addNotificationToMessage(MessageCreateBuilder messageBuilder, List<Role> rolesToMention, Guild guild) {
        if (notifyEventRoles.acquirePermission()) {
            try {
                boolean canMentionAny = guild.getSelfMember().hasPermission(Permission.MESSAGE_MENTION_EVERYONE);
                rolesToMention.forEach(role -> {
                    if (!canMentionAny && !role.isMentionable()) {
                        log.warn(
                                "Skipping ping of @{} in guild '{}': bot lacks MENTION_EVERYONE and the role is not mentionable.",
                                role.getName(),
                                guild.getName());
                        return;
                    }
                    messageBuilder
                            .mentionRoles(role.getId())
                            .addContent(role.getAsMention())
                            .addContent("\n");
                });
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

    /** Bulk variant — resolves multiple users in a guild in a single Gateway request. */
    public java.util.List<Member> getMembersFromServer(long serverId, long[] userIds) {
        return discordAuthService.getMembers(serverId, userIds);
    }

    public boolean isUserMemberOfServer(long serverId, long userId) {
        return discordAuthService.isMember(serverId, userId);
    }

    public boolean isUserOrganiserOfServer(long serverId, long userId) {
        return discordAuthService.isEventOrganiser(serverId, userId);
    }

    @Observed(name = "discord.sort-active-channels")
    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortActiveChannels() {
        for (dev.tylercash.event.discord.Guild row : guildRepository.findAllByActiveTrue()) {
            try {
                Category category = getEventCategory(row.getGuildId());
                String separator = row.getSeparatorChannel() == null ? "" : row.getSeparatorChannel();
                sortChannelsByEventDate(category, separator, row.getGuildId());
            } catch (Exception e) {
                log.warn("Failed to sort active channels for guild {}: {}", row.getGuildId(), e.getMessage());
            }
        }
    }

    @Observed(name = "discord.sort-archive-channels")
    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void sortArchiveChannels() {
        for (dev.tylercash.event.discord.Guild row : guildRepository.findAllByActiveTrue()) {
            try {
                Category category = resolveArchivedCategory(row.getGuildId());
                sortChannelsByChannelName(category);
            } catch (Exception e) {
                log.warn("Failed to sort archive channels for guild {}: {}", row.getGuildId(), e.getMessage());
            }
        }
    }

    public void sortChannelsByEventDate(Category eventCategory, String separator, long guildId) {
        List<TextChannel> channels = eventCategory.getTextChannels();
        List<Long> channelIds = channels.stream().map(TextChannel::getIdLong).toList();

        Map<Long, ZonedDateTime> channelDateMap = eventRepository.findByChannelIdIn(channelIds).stream()
                .collect(Collectors.toMap(Event::getChannelId, Event::getDateTime));
        Map<Long, Long> privateToMainChannelId = eventRepository.findByPrivateChannelIdIn(channelIds).stream()
                .filter(e -> e.getPrivateChannelId() != null)
                .collect(Collectors.toMap(Event::getPrivateChannelId, Event::getChannelId));

        // Orphans: channels with no associated event. The guild's separator channel also has no
        // event, but it is a permanent divider — exclude it so it is never archived. In the
        // ordering itself the separator is treated like any other orphan and floats to the front.
        List<TextChannel> orphans = channels.stream()
                .filter(ch -> !channelDateMap.containsKey(ch.getIdLong()))
                .filter(ch -> !privateToMainChannelId.containsKey(ch.getIdLong()))
                .filter(ch -> !ch.getName().equalsIgnoreCase(separator))
                .toList();

        if (!orphans.isEmpty()) {
            log.warn(
                    "Found {} orphaned channel(s) in events category with no matching event: {}",
                    orphans.size(),
                    orphans.stream().map(TextChannel::getName).toList());
        }

        if (featureToggles.isArchiveOrphanedChannels()) {
            OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(ORPHAN_AGE_DAYS);
            Category archiveCategory = resolveArchivedCategory(guildId);
            for (TextChannel orphan : orphans) {
                if (orphan.getTimeCreated().isBefore(cutoff)) {
                    log.info("Archiving orphaned channel '{}' (created {})", orphan.getName(), orphan.getTimeCreated());
                    JdaObservations.queue(
                            orphan.getManager().setParent(archiveCategory).sync(),
                            "discord.channel.archive-orphan.queue",
                            observationRegistry);
                }
            }
        }

        discordChannelService.reorderChannels(
                eventCategory, ChannelOrdering.byEventDate(channelDateMap, privateToMainChannelId));
    }

    public void sortChannelsByChannelName(Category category) {
        discordChannelService.sortChannelsByChannelName(category);
    }

    @Observed(name = "discord.delete-channel")
    public void deleteEventChannel(Event event) {
        discordChannelService.deleteChannel(event.getChannelId());
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.create-private-channel")
    public void createPrivateEventChannel(Event event) {
        if (event.getPrivateChannelId() != null) {
            return;
        }
        if (event.getAcceptedRoleId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Event roles have not been created yet — please wait and try again");
        }
        Guild guild = jda.getGuildById(event.getServerId());
        Category category = resolvePlannedCategory(event.getServerId());
        String channelName = DiscordUtil.getChannelNameFromEvent(event) + "-private";
        TextChannel channel = discordChannelService.createPrivateTextChannel(
                category, channelName, guild.getPublicRole().getIdLong(), event.getAcceptedRoleId());
        event.setPrivateChannelId(channel.getIdLong());
        Message alert = channel.sendMessage(
                        "⚠️ **Private Channel** — This channel is for sharing private event details only. "
                                + "Please keep all discussion in the main event channel. Do not chat here.")
                .complete();
        try {
            alert.pin().complete();
        } catch (InsufficientPermissionException e) {
            log.warn("Skipping pin of privacy notice in #{}: bot lacks PIN_MESSAGES.", channel.getName());
        }
        // Hide the auto-generated "BotName pinned a message" system notification.
        // Discord sets the author of CHANNEL_PINNED_ADD to the user who pinned,
        // so the bot is deleting its own message — no MANAGE_MESSAGES needed.
        channel.getHistory().retrievePast(5).complete().stream()
                .filter(m -> m.getType() == net.dv8tion.jda.api.entities.MessageType.CHANNEL_PINNED_ADD)
                .forEach(m ->
                        JdaObservations.queue(m.delete(), "discord.message.delete-system.queue", observationRegistry));
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
        Category archiveCategory = resolveArchivedCategory(event.getServerId());
        discordChannelService.moveChannelToCategory(eventChannel, archiveCategory);
        discordChannelService.sortChannelsByChannelName(archiveCategory);
    }

    public TextChannel getChannel(Event event) {
        return discordChannelService.getTextChannel(event.getChannelId());
    }

    /**
     * Posts a {@link MessageEmbed} into the event's Discord channel. No-op if the
     * channel cannot be found.
     */
    public void sendEmbedToEventChannel(Event event, MessageEmbed embed) {
        TextChannel channel = discordChannelService.getTextChannel(event.getChannelId());
        if (channel == null) {
            log.warn("Cannot send embed to event {}: channel {} not found", event.getId(), event.getChannelId());
            return;
        }
        JdaObservations.queue(
                channel.sendMessageEmbeds(embed), "discord.message.send-embed.queue", observationRegistry);
    }

    /** Sends a plain-content message to the event's Discord channel. Returns the new message snowflake. */
    @CircuitBreaker(name = "discord")
    public Long sendContentToEventChannel(Event event, String content) {
        TextChannel channel = discordChannelService.getTextChannel(event.getChannelId());
        if (channel == null) {
            log.warn("Cannot send content to event {}: channel {} not found", event.getId(), event.getChannelId());
            return null;
        }
        Message msg = channel.sendMessage(content).complete();
        return msg.getIdLong();
    }

    /**
     * Replies to {@code parentMessageId} in the event's channel with the given content.
     * Returns {@code true} on success, {@code false} if the parent message could not be
     * retrieved (deleted, channel changed, permissions) — the caller is expected to
     * fall back to {@link #sendContentToEventChannel(Event, String)} in that case.
     */
    @CircuitBreaker(name = "discord")
    public Boolean replyToMessage(Event event, long parentMessageId, String content) {
        TextChannel channel = discordChannelService.getTextChannel(event.getChannelId());
        if (channel == null) return false;
        try {
            Message parent = channel.retrieveMessageById(parentMessageId).complete();
            parent.reply(content).complete();
            return true;
        } catch (ErrorResponseException e) {
            log.info(
                    "TfNSW reply target {} missing in channel {} for event {}: {}",
                    parentMessageId,
                    event.getChannelId(),
                    event.getId(),
                    e.getMeaning());
            return false;
        }
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.send-album-link")
    public void sendAlbumLink(Event event, String albumUrl) {
        if (event.getNotifications().contains(new Notification(NotificationType.ALBUM_LINK))) {
            return;
        }
        TextChannel channel = getChannel(event);
        Message message = channel.sendMessage("[Post photos of the event in the album!](" + albumUrl + ")")
                .complete();
        pinSilently(message, "album link");
        event.getNotifications()
                .add(new Notification(
                        NotificationType.ALBUM_LINK, ZonedDateTime.now(clock).toInstant(), message.getIdLong()));
    }

    @Observed(name = "discord.create-event-roles")
    public void createEventRoles(Event event) {
        Guild guild = jda.getGuildById(event.getServerId());
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            log.warn(
                    "Skipping per-event role creation for '{}' in guild '{}': bot lacks MANAGE_ROLES. RSVP buttons will still work but won't toggle roles.",
                    event.getName(),
                    guild.getName());
            return;
        }
        String baseName = event.getName();
        if (baseName.length() > 89) {
            baseName = baseName.substring(0, 89);
        }
        try {
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
        } catch (InsufficientPermissionException e) {
            log.warn(
                    "Stopped creating event roles for '{}' partway: {}. Existing role IDs are kept; missing ones stay null.",
                    event.getName(),
                    e.getMessage());
        }
    }

    @Observed(name = "discord.delete-event-roles")
    public void deleteEventRoles(Event event) {
        Guild guild = jda.getGuildById(event.getServerId());
        discordRoleService.deleteRole(guild, event.getAcceptedRoleId());
        discordRoleService.deleteRole(guild, event.getDeclinedRoleId());
        discordRoleService.deleteRole(guild, event.getMaybeRoleId());
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.assign-event-role")
    public void assignEventRole(
            Event event, String snowflake, dev.tylercash.event.event.model.AttendanceStatus status) {
        Guild guild = jda.getGuildById(event.getServerId());
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

    @CircuitBreaker(name = "discord")
    public void removeAllEventRoles(Event event, String snowflake) {
        Guild guild = jda.getGuildById(event.getServerId());
        Member member = guild.retrieveMemberById(snowflake).complete();
        if (member != null) {
            discordRoleService.removeRoleFromMember(guild, member, event.getAcceptedRoleId());
            discordRoleService.removeRoleFromMember(guild, member, event.getDeclinedRoleId());
            discordRoleService.removeRoleFromMember(guild, member, event.getMaybeRoleId());
        }
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.send-pre-event-notification")
    public void sendMessageBeforeEvent(Event event) {
        if (event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT))) {
            return;
        }
        notifyEventRoles.executeRunnable(() -> {
            Guild server = jda.getGuildById(event.getServerId());
            MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                    .addContent("**" + event.getName() + "**  starting at " + "<t:"
                            + event.getDateTime().toEpochSecond() + ":t>\n");
            event.getAccepted().stream()
                    .filter(user ->
                            user.getSnowflake() != null && !user.getSnowflake().isBlank())
                    .map(user -> getMemberFromServer(event.getServerId(), Long.parseLong(user.getSnowflake())))
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
