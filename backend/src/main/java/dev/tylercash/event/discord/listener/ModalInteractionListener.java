package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.MetricsService;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Log4j2
@Component
@AllArgsConstructor
public class ModalInteractionListener extends ListenerAdapter {
    public static final String PLUS_ONE = "Add +1";
    public static final String PLUS_ONE_ID = "plus1";
    private final Clock clock;
    private final MetricsService metricsService;
    private final EventRepository eventRepository;
    private final EmbedService embedService;

    @Override
    public void onModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        long startTime = System.nanoTime();
        ModalInteraction interaction = modalInteractionEvent.getInteraction();
        Event event = eventRepository.findByChannelId(modalInteractionEvent.getChannel().getIdLong());
        String plus1Name = interaction.getValues().get(0).getAsString();
        event.getAccepted().add(Attendee.createDiscordAttendee(null, plus1Name));
        modalInteractionEvent.editMessageEmbeds(embedService.getMessage(event, clock)).queue();
        eventRepository.save(event);
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info("User {} interacting with status {} on event {}, taking {}ms", modalInteractionEvent.getUser().getEffectiveName(), plus1Name, event.getName(), duration);
    }
}
