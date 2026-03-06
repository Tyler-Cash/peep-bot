package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import lombok.extern.log4j.Log4j2;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
public class PostAlbumLink extends ScheduledEventProcessor {
    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;
    private final Clock clock;

    public PostAlbumLink(DiscordService discordService, EventRepository eventRepository,
                         ImmichConfiguration immichConfiguration, ImmichService immichService, Clock clock) {
        super(discordService, eventRepository);
        this.immichConfiguration = immichConfiguration;
        this.immichService = immichService;
        this.clock = clock;
    }

    @Override
    @Scheduled(fixedRate = 1000 * 60)
    @SchedulerLock(name = "postAlbumLink")
    public void processAll() {
        if (!immichConfiguration.isEnabled()) {
            return;
        }
        super.processAll();
    }

    boolean shouldEventBeProcessed(Event event) {
        boolean afterOneHour = ZonedDateTime.now(clock).isAfter(event.getDateTime().plusHours(1));
        boolean hasPlanningState = event.getState().equals(EventState.PLANNED);
        boolean notNotifiedAlready = !event.getNotifications().contains(new Notification(NotificationType.ALBUM_LINK));
        return afterOneHour && hasPlanningState && notNotifiedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }

        // Fallback: attempt album creation if missing
        if (event.getImmichAlbumId() == null) {
            immichService.createAlbum(event.getName(), event.getDescription())
                    .ifPresent(event::setImmichAlbumId);
        }

        // Fallback: attempt shared link creation if album exists but link missing
        if (event.getImmichAlbumId() != null && event.getImmichShareKey() == null) {
            immichService.createSharedLink(event.getImmichAlbumId())
                    .ifPresent(event::setImmichShareKey);
        }

        if (event.getImmichShareKey() == null) {
            log.warn("Cannot post album link for event '{}': no share key available", event.getName());
            return;
        }

        String albumUrl = immichService.getShareUrl(event.getImmichShareKey());
        log.info("Posting album link for event: {}", event.getName());
        discordService.sendAlbumLink(event, albumUrl);
        eventRepository.save(event);
    }
}
