package dev.tylercash.event.discord.listener;

import dev.tylercash.event.contract.ContractService;
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
import java.util.Arrays;
import java.util.List;
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
    private final ObjectProvider<ContractService> contractServiceProvider;

    public ModalInteractionListener(
            Clock clock,
            ObservationRegistry observationRegistry,
            EventRepository eventRepository,
            EmbedService embedService,
            ObjectProvider<EventService> eventServiceProvider,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService,
            ObjectProvider<ContractService> contractServiceProvider) {
        this.clock = clock;
        this.observationRegistry = observationRegistry;
        this.eventRepository = eventRepository;
        this.embedService = embedService;
        this.eventServiceProvider = eventServiceProvider;
        this.attendanceService = attendanceService;
        this.discordUserCacheService = discordUserCacheService;
        this.contractServiceProvider = contractServiceProvider;
    }

    @Override
    public void onModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        Observation.createNotStarted("discord.modal-interaction", observationRegistry)
                .lowCardinalityKeyValue("interaction.type", "modal")
                .observe(() -> handleModalInteraction(modalInteractionEvent));
    }

    private void handleModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        ModalInteraction interaction = modalInteractionEvent.getInteraction();

        if ("contract_create".equals(interaction.getModalId())) {
            handleContractCreateModal(modalInteractionEvent);
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
        discordUserCacheService.upsertUser(ownerSnowflake, ownerDisplayName);

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

    private void handleContractCreateModal(@NonNull ModalInteractionEvent modalInteractionEvent) {
        ModalInteraction interaction = modalInteractionEvent.getInteraction();
        modalInteractionEvent.deferReply(true).queue();

        try {
            String title = interaction.getValue("title").getAsString();
            String description = interaction.getValue("description") != null
                    ? interaction.getValue("description").getAsString()
                    : null;
            if (description != null && description.isBlank()) {
                description = null;
            }

            String outcomesRaw = interaction.getValue("outcomes") != null
                    ? interaction.getValue("outcomes").getAsString()
                    : null;
            List<String> outcomeLabels;
            if (outcomesRaw == null || outcomesRaw.isBlank()) {
                outcomeLabels = List.of("YES", "NO");
            } else {
                outcomeLabels = Arrays.stream(outcomesRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }

            long seedAmount =
                    Long.parseLong(interaction.getValue("seed").getAsString().trim());

            String userId = modalInteractionEvent.getUser().getId();
            contractServiceProvider.getObject().createContract(userId, title, description, outcomeLabels, seedAmount);

            modalInteractionEvent
                    .getHook()
                    .sendMessage("\u2705 Prediction contract created!")
                    .setEphemeral(true)
                    .queue();
        } catch (Exception e) {
            log.warn("Contract creation failed", e);
            modalInteractionEvent
                    .getHook()
                    .sendMessage("\u274C Error: " + e.getMessage())
                    .setEphemeral(true)
                    .queue();
        }
    }
}
