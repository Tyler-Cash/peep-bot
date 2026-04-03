package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.*;
import static dev.tylercash.event.discord.DiscordUtil.getMonthDayFromChannelName;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.*;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static java.util.concurrent.TimeUnit.MINUTES;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import dev.tylercash.event.security.dev.DevUserProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
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
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
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
    private final Optional<DevUserProperties> devUserProperties;

    @Observed(name = "discord.create-channel")
    public TextChannel createEventChannel(Event event) {
        Category category = getEventCategory(discordConfiguration.getGuildId());
        ChannelAction<TextChannel> textChannelChannelAction = category.createTextChannel(
                        DiscordUtil.getChannelNameFromEvent(event))
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
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + EVENT_CATEGORY + "\"");
        }
        return categories.get(0);
    }

    public @NotNull Category getEventCategory(long serverId) {
        return getChannelCategory(serverId, EVENT_CATEGORY);
    }

    @Observed(name = "discord.post-message")
    public Message postEventMessage(Event event, TextChannel channel) {
        List<Role> rolesToMention = getRoles(channel.getGuild().getIdLong(), discordConfiguration.getEventsRole());
        MessageCreateBuilder messageBuilder = new MessageCreateBuilder()
                .addEmbeds(embedService.getMessage(event, clock))
                .addComponents(List.of(ActionRow.of(
                        Button.secondary(ACCEPTED, ACCEPTED_EMOJI),
                        Button.secondary(DECLINED, DECLINED_EMOJI),
                        Button.secondary(MAYBE, MAYBE_EMOJI),
                        Button.secondary(PLUS_ONE_ID, PLUS_ONE))));
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
        getChannel(event)
                .editMessageEmbedsById(event.getMessageId(), embedService.getMessage(event, clock))
                .queue();
    }

    public void removeEventButtons(Event event) {
        getChannel(event).editMessageComponentsById(event.getMessageId()).queue();
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
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        Member member = getMemberFromServer(serverId, userId);
        return member != null
                && member.getRoles().stream()
                        .anyMatch(role -> role.getName().equalsIgnoreCase(discordConfiguration.getAdminRole()));
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
        Category category = getArchiveCategory(discordConfiguration.getGuildId());
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
            Category archiveCategory = getArchiveCategory(discordConfiguration.getGuildId());
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
        DateTimeFormatter monthParser = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(Locale.ENGLISH);

        List<TextChannel> channels = new ArrayList<>(category.getTextChannels());
        channels.sort(Comparator.comparing(channel -> getMonthDayFromChannelName(channel, monthParser)));

        for (int i = 0; i < channels.size(); i++) {
            channels.get(i).getManager().setPosition(i).queue();
        }
    }

    @Observed(name = "discord.delete-channel")
    public void deleteEventChannel(Event event) {
        jda.getGuildById(discordConfiguration.getGuildId())
                .getChannelById(TextChannel.class, event.getChannelId())
                .delete()
                .queue();
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
        Category category = getEventCategory(discordConfiguration.getGuildId());
        String channelName = DiscordUtil.getChannelNameFromEvent(event) + "-private";
        TextChannel channel = category.createTextChannel(channelName)
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), 0L, Permission.VIEW_CHANNEL.getRawValue())
                .addRolePermissionOverride(
                        event.getAcceptedRoleId(),
                        Permission.VIEW_CHANNEL.getRawValue()
                                | Permission.MESSAGE_SEND.getRawValue()
                                | Permission.MESSAGE_HISTORY.getRawValue(),
                        0L)
                .complete();
        event.setPrivateChannelId(channel.getIdLong());
        Message alert = channel.sendMessage(
                        "⚠️ **Private Channel** — This channel is for sharing private event details only. "
                                + "Please keep all discussion in the main event channel. Do not chat here.")
                .complete();
        alert.pin().complete();
        channel.getHistory().retrievePast(5).complete().stream()
                .filter(m -> m.getType() == net.dv8tion.jda.api.entities.MessageType.CHANNEL_PINNED_MESSAGE)
                .forEach(m -> m.delete().queue());
    }

    @Observed(name = "discord.delete-private-channel")
    public void deletePrivateEventChannel(Event event) {
        if (event.getPrivateChannelId() == null) {
            return;
        }
        TextChannel channel = jda.getChannelById(TextChannel.class, event.getPrivateChannelId());
        if (channel != null) {
            channel.delete().queue();
        }
    }

    @Observed(name = "discord.archive-channel")
    public void archiveEventChannel(Event event) {
        TextChannel eventChannel = getChannel(event);
        Category category = getArchiveCategory(discordConfiguration.getGuildId());
        eventChannel.getManager().setParent(category).sync().queue();
        sortChannelsByChannelName(category);
    }

    public TextChannel getChannel(Event event) {
        return jda.getChannelById(TextChannel.class, event.getChannelId());
    }

    private List<Role> getRoles(long serverId, String role) {
        List<Role> rolesByName = jda.getGuildById(serverId).getRolesByName(role, true);
        if (rolesByName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + role);
        }
        return rolesByName;
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
            Role accepted = guild.createRole().setName(baseName + " - Accepted").complete();
            event.setAcceptedRoleId(accepted.getIdLong());
        }
        if (event.getDeclinedRoleId() == null) {
            Role declined = guild.createRole().setName(baseName + " - Declined").complete();
            event.setDeclinedRoleId(declined.getIdLong());
        }
        if (event.getMaybeRoleId() == null) {
            Role maybe = guild.createRole().setName(baseName + " - Maybe").complete();
            event.setMaybeRoleId(maybe.getIdLong());
        }
    }

    @Observed(name = "discord.delete-event-roles")
    public void deleteEventRoles(Event event) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        deleteRoleById(guild, event.getAcceptedRoleId());
        deleteRoleById(guild, event.getDeclinedRoleId());
        deleteRoleById(guild, event.getMaybeRoleId());
    }

    private void deleteRoleById(Guild guild, Long roleId) {
        if (roleId == null) {
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role != null) {
            role.delete().queue();
        }
    }

    @Observed(name = "discord.assign-event-role")
    public void assignEventRole(
            Event event, String snowflake, dev.tylercash.event.event.model.AttendanceStatus status) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        Member member = guild.retrieveMemberById(snowflake).complete();
        if (member == null) {
            return;
        }
        removeAllEventRolesFromMember(guild, event, member);
        Long roleId =
                switch (status) {
                    case ACCEPTED -> event.getAcceptedRoleId();
                    case DECLINED -> event.getDeclinedRoleId();
                    case MAYBE -> event.getMaybeRoleId();
                    default -> null;
                };
        if (roleId != null) {
            Role role = guild.getRoleById(roleId);
            if (role != null) {
                guild.addRoleToMember(member, role).queue();
            }
        }
    }

    public void removeAllEventRoles(Event event, String snowflake) {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        Member member = guild.retrieveMemberById(snowflake).complete();
        if (member != null) {
            removeAllEventRolesFromMember(guild, event, member);
        }
    }

    private void removeAllEventRolesFromMember(Guild guild, Event event, Member member) {
        for (Long roleId : List.of(event.getAcceptedRoleId(), event.getDeclinedRoleId(), event.getMaybeRoleId())) {
            if (roleId == null) {
                continue;
            }
            Role role = guild.getRoleById(roleId);
            if (role != null && member.getRoles().contains(role)) {
                guild.removeRoleFromMember(member, role).queue();
            }
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
