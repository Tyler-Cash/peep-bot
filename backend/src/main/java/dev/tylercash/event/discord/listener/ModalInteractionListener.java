package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.discord.JdaObservations;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final Executor executor;

    public ModalInteractionListener(
            Clock clock,
            ObservationRegistry observationRegistry,
            EventRepository eventRepository,
            EmbedService embedService,
            ObjectProvider<EventService> eventServiceProvider,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService,
            @Qualifier("discordListenerExecutor") Executor executor) {
        this.clock = clock;
        this.observationRegistry = observationRegistry;
        this.eventRepository = eventRepository;
        this.embedService = embedService;
        this.eventServiceProvider = eventServiceProvider;
        this.attendanceService = attendanceService;
        this.discordUserCacheService = discordUserCacheService;
        this.executor = executor;
    }

    @Override
    public void onModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        ModalInteraction interaction = modalInteractionEvent.getInteraction();
        if (!PLUS_ONE_ID.equals(interaction.getModalId())) {
            log.warn("Unrecognised modal ID: {}", interaction.getModalId());
            return;
        }

        Event event = eventRepository.findByChannelId(
                modalInteractionEvent.getChannel().getIdLong());
        if (event == null || eventServiceProvider.getObject().isCompleted(event)) {
            JdaObservations.queue(
                    modalInteractionEvent
                            .reply("Attendance is locked for this event.")
                            .setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

        JdaObservations.queue(modalInteractionEvent.deferEdit(), "discord.defer-edit.queue", observationRegistry);

        executor.execute(() -> Observation.createNotStarted("discord.modal-interaction", observationRegistry)
                .lowCardinalityKeyValue("interaction.type", "modal")
                .lowCardinalityKeyValue("modal.id", interaction.getModalId())
                .observe(() -> handleModalInteraction(modalInteractionEvent, event)));
    }

    private void handleModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent, @NonNull Event event) {
        MDC.put("eventId", event.getId().toString());
        if (modalInteractionEvent.getGuild() != null) {
            MDC.put("guildId", Long.toString(modalInteractionEvent.getGuild().getIdLong()));
        }
        if (modalInteractionEvent.getChannel() != null) {
            MDC.put(
                    "channelId",
                    Long.toString(modalInteractionEvent.getChannel().getIdLong()));
        }
        MDC.put("interactionId", modalInteractionEvent.getId());
        try {
            String ownerSnowflake = modalInteractionEvent.getUser().getId();
            String ownerDisplayName = DiscordUtil.getUserDisplayName(modalInteractionEvent.getMember());
            String ownerUsername = modalInteractionEvent.getUser().getName();
            discordUserCacheService.upsertUser(
                    ownerSnowflake, ownerDisplayName, ownerUsername, null, event.getServerId());

            String plus1Name =
                    modalInteractionEvent.getInteraction().getValues().get(0).getAsString();
            attendanceService.recordAttendance(
                    event.getId(), null, "[+1] " + plus1Name, AttendanceStatus.ACCEPTED, ownerSnowflake);

            eventServiceProvider.getObject().populateAttendance(event);
            JdaObservations.queue(
                    modalInteractionEvent.getHook().editOriginalEmbeds(embedService.getMessage(event, clock)),
                    "discord.edit-original",
                    observationRegistry);

            log.info(
                    "User {} adding +1 '{}' on event {}",
                    modalInteractionEvent.getUser().getEffectiveName(),
                    plus1Name,
                    event.getName());
        } finally {
            MDC.remove("eventId");
            MDC.remove("guildId");
            MDC.remove("channelId");
            MDC.remove("interactionId");
        }
    }
}
