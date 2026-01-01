package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.MetricsService;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static dev.tylercash.event.discord.DiscordUtil.handleMessageComponentInteraction;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;

@Log4j2
@Component
@AllArgsConstructor
public class ButtonInteractionListener extends ListenerAdapter {
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String MAYBE = "maybe";
    public static final String MODAL_PLACEHOLDER = "Enter name of +1";
    private final Clock clock;
    private final MetricsService metricsService;
    private final EventRepository eventRepository;
    private final EmbedService embedService;

    private static void replyWithModal(@NonNull ButtonInteractionEvent buttonInteractionEvent) {
        TextInput plusOne = TextInput.create(PLUS_ONE_ID, PLUS_ONE, TextInputStyle.SHORT)
                .setPlaceholder(MODAL_PLACEHOLDER)
                .setRequiredRange(3, 20)
                .build();

        Modal modal = Modal.create(PLUS_ONE_ID, PLUS_ONE)
                .addComponents(ActionRow.of(plusOne))
                .build();
        buttonInteractionEvent.replyModal(modal).queue();
    }

    @Override
    public void onButtonInteraction(@NonNull ButtonInteractionEvent buttonInteractionEvent) {
        long startTime = System.nanoTime();
        Event event = eventRepository.findByMessageId(buttonInteractionEvent.getMessageIdLong());
        if (event == null) {
            log.warn("Unrecognized event message ID {}", buttonInteractionEvent.getMessageIdLong());
            return;
        }
        String eventType = buttonInteractionEvent.getButton().getId();
        if (Objects.equals(eventType, PLUS_ONE_ID)) {
            replyWithModal(buttonInteractionEvent);
            return;
        }
        handleMessageComponentInteraction(event, Objects.requireNonNull(buttonInteractionEvent.getMember()), Objects.requireNonNull(eventType));
        updateUserRoles(buttonInteractionEvent, event, eventType);
        buttonInteractionEvent.editMessageEmbeds(embedService.getMessage(event, clock)).complete();
        eventRepository.save(event);
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info("User {} interacting with status {} on event {}, taking {}ms", buttonInteractionEvent.getMember().getEffectiveName(), eventType, event.getName(), duration);
    }

    private void updateUserRoles(ButtonInteractionEvent buttonInteractionEvent, Event event, String eventType) {
        Guild guild = buttonInteractionEvent.getGuild();
        if (guild == null) {
            log.error("Could not find guild from button interaction.");
            return;
        }
        Member member = buttonInteractionEvent.getMember();
        if (member == null) {
            log.error("Could not find member from button interaction.");
            return;
        }

        Role acceptedRole = getRoleFromId(guild, event.getAcceptedRoleId());
        Role maybeRole = getRoleFromId(guild, event.getMaybeRoleId());
        Role declinedRole = getRoleFromId(guild, event.getDeclinedRoleId());

        // Remove existing roles
        if (acceptedRole != null) guild.removeRoleFromMember(member, acceptedRole).queue();
        if (maybeRole != null) guild.removeRoleFromMember(member, maybeRole).queue();
        if (declinedRole != null) guild.removeRoleFromMember(member, declinedRole).queue();

        // Add the new role
        switch (eventType) {
            case ACCEPTED:
                if (acceptedRole != null) guild.addRoleToMember(member, acceptedRole).queue();
                break;
            case MAYBE:
                if (maybeRole != null) guild.addRoleToMember(member, maybeRole).queue();
                break;
            case DECLINED:
                if (declinedRole != null) guild.addRoleToMember(member, declinedRole).queue();
                break;
        }
    }

    private Role getRoleFromId(Guild guild, long roleId) {
        if (roleId == 0L) {
            return null;
        }
        return guild.getRoleById(roleId);
    }
}

