package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.NotificationType;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Component
public class ReactionListener extends ListenerAdapter {

    private final EventRepository eventRepository;
    private final Map<Long, UUID> messageIdToEventIdCache = new ConcurrentHashMap<>();

    public ReactionListener(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
        refreshCache();
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (event.getUser().isBot()) {
            return;
        }

        UUID eventId = messageIdToEventIdCache.get(event.getMessageIdLong());
        if (eventId != null) {
            if (Objects.equals(event.getReaction().getEmoji().getName(), "❌")) {
                muteUser(event);
            }
        }
    }

    @Scheduled(fixedRate = 1000 * 60)
    public void refreshCache() {
        messageIdToEventIdCache.clear();
        eventRepository.findAllByStateEquals(null, dev.tylercash.event.event.model.EventState.PLANNED).forEach(e -> {
            e.getNotifications().stream()
                .filter(n -> n.getType().equals(NotificationType.CONFIRM_ATTENDANCE))
                .forEach(n -> messageIdToEventIdCache.put(n.getMessageId(), e.getId()));
        });
    }

    private void muteUser(MessageReactionAddEvent event) {
        Guild guild = event.getGuild();
        List<Role> roles = guild.getRolesByName("event-mute-maybe-attendance", true);
        Role role;
        if (roles.isEmpty()) {
            role = guild.createRole().setName("event-mute-maybe-attendance").complete();
        } else {
            role = roles.get(0);
        }
        guild.addRoleToMember(Objects.requireNonNull(event.getMember()), role).queue();
    }
}
