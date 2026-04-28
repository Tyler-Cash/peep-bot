package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ModalInteractionListener extends ListenerAdapter {
    public static final String PLUS_ONE = "Add +1";
    public static final String PLUS_ONE_ID = "plus1";
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    private final EventRepository eventRepository;
    private final EmbedService embedService;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;

    public ModalInteractionListener(
            Clock clock,
            ObservationRegistry observationRegistry,
            EventRepository eventRepository,
            EmbedService embedService,
            ObjectProvider<EventService> eventServiceProvider,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService) {
        this.clock = clock;
        this.observationRegistry = observationRegistry;
        this.eventRepository = eventRepository;
        this.embedService = embedService;
        this.eventServiceProvider = eventServiceProvider;
        this.attendanceService = attendanceService;
        this.discordUserCacheService = discordUserCacheService;
    }

    @Override
    public void onModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        Observation.createNotStarted("discord.modal-interaction", observationRegistry)
                .lowCardinalityKeyValue("interaction.type", "modal")
                .observe(() -> handleModalInteraction(modalInteractionEvent));
    }

    private void handleModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        ModalInteraction interaction = modalInteractionEvent.getInteraction();

        if (!PLUS_ONE_ID.equals(interaction.getModalId())) {
            log.warn("Unrecognised modal ID: {}", interaction.getModalId());
            return;
        }

        Event event = eventRepository.findByChannelId(
                modalInteractionEvent.getChannel().getIdLong());
        if (event == null || eventServiceProvider.getObject().isCompleted(event)) {
            modalInteractionEvent
                    .reply("Attendance is locked for this event.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        MDC.put("eventId", event.getId().toString());

        String ownerSnowflake = modalInteractionEvent.getUser().getId();
        String ownerDisplayName = DiscordUtil.getUserDisplayName(modalInteractionEvent.getMember());
        String ownerUsername = modalInteractionEvent.getUser().getName();
        discordUserCacheService.upsertUser(ownerSnowflake, ownerDisplayName, ownerUsername, null, event.getServerId());

        String plus1Name = interaction.getValues().get(0).getAsString();
        attendanceService.recordAttendance(
                event.getId(), null, "[+1] " + plus1Name, AttendanceStatus.ACCEPTED, ownerSnowflake);

        eventServiceProvider.getObject().populateAttendance(event);
        modalInteractionEvent
                .editMessageEmbeds(embedService.getMessage(event, clock))
                .queue();

        log.info(
                "User {} adding +1 '{}' on event {}",
                modalInteractionEvent.getUser().getEffectiveName(),
                plus1Name,
                event.getName());
    }
}
