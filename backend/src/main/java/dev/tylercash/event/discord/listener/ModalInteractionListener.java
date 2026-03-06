package dev.tylercash.event.discord.listener;

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
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class ModalInteractionListener extends ListenerAdapter {
    public static final String PLUS_ONE = "Add +1";
    public static final String PLUS_ONE_ID = "plus1";
    private final Clock clock;
    private final MetricsService metricsService;
    private final EventRepository eventRepository;
    private final EmbedService embedService;
    private final EventService eventService;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;

    public ModalInteractionListener(
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

    @Override
    public void onModalInteraction(@NonNull ModalInteractionEvent modalInteractionEvent) {
        long startTime = System.nanoTime();
        ModalInteraction interaction = modalInteractionEvent.getInteraction();
        Event event = eventRepository.findByChannelId(
                modalInteractionEvent.getChannel().getIdLong());
        if (event == null || eventService.isCompleted(event)) {
            modalInteractionEvent
                    .reply("Attendance is locked for this event.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String ownerSnowflake = modalInteractionEvent.getUser().getId();
        String ownerDisplayName = DiscordUtil.getUserDisplayName(modalInteractionEvent.getMember());
        discordUserCacheService.upsertUser(ownerSnowflake, ownerDisplayName);

        String plus1Name = interaction.getValues().get(0).getAsString();
        attendanceService.recordAttendance(
                event.getId(), null, "[+1] " + plus1Name, AttendanceStatus.ACCEPTED, ownerSnowflake);

        eventService.populateAttendance(event);
        modalInteractionEvent
                .editMessageEmbeds(embedService.getMessage(event, clock))
                .queue();

        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        metricsService.getDiscordMessageComponentEventTimer().record(duration, TimeUnit.MILLISECONDS);
        log.info(
                "User {} adding +1 '{}' on event {}, taking {}ms",
                modalInteractionEvent.getUser().getEffectiveName(),
                plus1Name,
                event.getName(),
                duration);
    }
}
