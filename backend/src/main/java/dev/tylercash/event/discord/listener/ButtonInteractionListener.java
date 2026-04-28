package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
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
import java.util.Objects;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ButtonInteractionListener extends ListenerAdapter {
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String MAYBE = "maybe";
    public static final String MODAL_PLACEHOLDER = "Enter name of +1";
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    private final EventRepository eventRepository;
    private final EmbedService embedService;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;
    private final ObjectProvider<DiscordService> discordServiceProvider;

    public ButtonInteractionListener(
            Clock clock,
            ObservationRegistry observationRegistry,
            EventRepository eventRepository,
            EmbedService embedService,
            ObjectProvider<EventService> eventServiceProvider,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService,
            ObjectProvider<DiscordService> discordServiceProvider) {
        this.clock = clock;
        this.observationRegistry = observationRegistry;
        this.eventRepository = eventRepository;
        this.embedService = embedService;
        this.eventServiceProvider = eventServiceProvider;
        this.attendanceService = attendanceService;
        this.discordUserCacheService = discordUserCacheService;
        this.discordServiceProvider = discordServiceProvider;
    }

    private static void replyWithModal(@NonNull ButtonInteractionEvent buttonInteractionEvent) {
        TextInput plusOne = TextInput.create(PLUS_ONE_ID, TextInputStyle.SHORT)
                .setPlaceholder(MODAL_PLACEHOLDER)
                .setRequiredRange(3, 20)
                .build();

        Modal modal = Modal.create(PLUS_ONE_ID, PLUS_ONE)
                .addComponents(Label.of(PLUS_ONE, plusOne))
                .build();
        buttonInteractionEvent.replyModal(modal).queue();
    }

    @Override
    public void onButtonInteraction(@NonNull ButtonInteractionEvent buttonInteractionEvent) {
        Observation.createNotStarted("discord.button-interaction", observationRegistry)
                .lowCardinalityKeyValue("interaction.type", "button")
                .observe(() -> handleButtonInteraction(buttonInteractionEvent));
    }

    private void handleButtonInteraction(@NonNull ButtonInteractionEvent buttonInteractionEvent) {
        Event event = eventRepository.findByMessageId(buttonInteractionEvent.getMessageIdLong());
        if (event == null) {
            log.warn("Unrecognized event message ID {}", buttonInteractionEvent.getMessageIdLong());
            return;
        }
        MDC.put("eventId", event.getId().toString());
        if (eventServiceProvider.getObject().isCompleted(event)) {
            buttonInteractionEvent
                    .reply("Attendance is locked for this event.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String eventType = buttonInteractionEvent.getButton().getCustomId();
        if (Objects.equals(eventType, PLUS_ONE_ID)) {
            replyWithModal(buttonInteractionEvent);
            return;
        }

        String userId =
                Objects.requireNonNull(buttonInteractionEvent.getMember()).getId();
        String displayName = DiscordUtil.getUserDisplayName(buttonInteractionEvent.getMember());
        String username = buttonInteractionEvent.getUser().getName();
        discordUserCacheService.upsertUser(userId, displayName, username, null, event.getServerId());

        AttendanceStatus status = mapButtonToStatus(Objects.requireNonNull(eventType));
        if (status != null) {
            AttendanceStatus resolvedStatus = attendanceService.flipAttendance(event.getId(), userId, null, status);
            try {
                if (resolvedStatus == AttendanceStatus.REMOVED) {
                    discordServiceProvider.getObject().removeAllEventRoles(event, userId);
                } else {
                    discordServiceProvider.getObject().assignEventRole(event, userId, resolvedStatus);
                }
            } catch (Exception e) {
                log.warn("Failed to update Discord role for user {} on event '{}'", userId, event.getName(), e);
            }
        }

        eventServiceProvider.getObject().populateAttendance(event);
        buttonInteractionEvent
                .editMessageEmbeds(embedService.getMessage(event, clock))
                .complete();

        log.info(
                "User {} interacting with status {} on event {}",
                buttonInteractionEvent.getMember().getEffectiveName(),
                eventType,
                event.getName());
    }

    private AttendanceStatus mapButtonToStatus(String buttonId) {
        return switch (buttonId) {
            case ACCEPTED -> AttendanceStatus.ACCEPTED;
            case DECLINED -> AttendanceStatus.DECLINED;
            case MAYBE -> AttendanceStatus.MAYBE;
            default -> null;
        };
    }
}
