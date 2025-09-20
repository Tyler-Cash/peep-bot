package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordConfiguration;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@AllArgsConstructor
public class MaybeConfirmationReactionListener extends ListenerAdapter {

    private final EventRepository eventRepository;
    private final DiscordConfiguration discordConfiguration;

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (event.getUser().isBot()) {
            return;
        }

        if (event.getReaction().getEmoji().getName().equals(DiscordConfiguration.DECLINED_EMOJI)) {
            eventRepository.findByMaybeConfirmationMessageId(event.getMessageIdLong()).ifPresent(eventData -> {
                handleDontNotify(event);
            });
        }
    }

    private void handleDontNotify(MessageReactionAddEvent event) {
        Guild guild = event.getGuild();
        Role role = getOrCreateDontNotifyRole(guild);
        guild.addRoleToMember(Objects.requireNonNull(event.getMember()), role).queue();
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
