package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.discord.EmbedService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.MetricsService;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class ButtonInteractionListener extends ListenerAdapter {
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String MAYBE = "maybe";
    public static final String MODAL_PLACEHOLDER = "Enter name of +1";
    private final Clock clock;
    private final MetricsService metricsService;
    private final EventRepository eventRepository;
    private final EmbedService embedService;
    private final EventService eventService;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;

    public ButtonInteractionListener(
            Clock clock,
            MetricsService metricsService,
            EventRepository eventRepository,
            EmbedService embedService,
            @Lazy EventService eventService,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService) {
        this.clock = clock;
        this.metricsService = metricsService;
        this.eventRepository = eventRepository;
        this.embedService = embedService;
        this.eventService = eventService;
        this.attendanceService = attendanceService;
        this.discordUserCacheService = discordUserCacheService;
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
        long startTime = System.nanoTime();
        Event event = eventRepository.findByMessageId(buttonInteractionEvent.getMessageIdLong());
        if (event == null) {
            log.warn("Unrecognized event message ID {}", buttonInteractionEvent.getMessageIdLong());
            return;
        }
        if (eventService.isCompleted(event)) {
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
        discordUserCacheService.upsertUser(userId, displayName);

        AttendanceStatus status = mapButtonToStatus(Objects.requireNonNull(eventType));
        if (status != null) {
            attendanceService.flipAttendance(event.getId(), userId, null, status);
        }

        eventService.populateAttendance(event);
        buttonInteractionEvent
                .editMessageEmbeds(embedService.getMessage(event, clock))
                .complete();

        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info(
                "User {} interacting with status {} on event {}, taking {}ms",
                buttonInteractionEvent.getMember().getEffectiveName(),
                eventType,
                event.getName(),
                duration);
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
