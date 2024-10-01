package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.MetricsService;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
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
        buttonInteractionEvent.editMessageEmbeds(embedService.getMessage(event, clock)).complete();
        eventRepository.save(event);
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info("User {} interacting with status {} on event {}, taking {}ms", buttonInteractionEvent.getMember().getEffectiveName(), eventType, event.getName(), duration);
    }
}

