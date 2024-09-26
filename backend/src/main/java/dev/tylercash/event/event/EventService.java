package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.HOURS;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final Clock clock;
    private final RateLimiter notifyEventRoles;

    public String createEvent(Event event) {
        ServerTextChannel channel = discordService.createEventChannel(event);
        Message message;
        try {
            message = discordService.postEventMessage(event, channel);
            event.setServerId(channel.getServer().getId());
            event.setChannelId(channel.getId());
            event.setMessageId(message.getId());
            eventRepository.save(event);
            initialEventNotification(event, channel);
            eventRepository.save(event);
        } catch (Exception e) {
            channel.delete();
            throw e;
        }
        discordService.pinMessage(message);
        discordService.sortChannels(discordService.getEventCategory());
        return "Created event for " + event.getName();
    }

    private void initialEventNotification(Event event, ServerTextChannel channel) {
        try {
            notifyEventRoles.executeRunnable(() -> discordService.initialNotificationAboutEvent(event, channel));
        } catch (RequestNotPermitted e) {
            log.warn("Notification to event role currently rate limited. {}", e.getMessage());
        }
    }

    public Event getEvent(UUID id) {
        Optional<Event> event = eventRepository.findById(id);
        if (event.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return event.get();
    }

    public Event updateEvent(Event event) {
        eventRepository.save(event);
        discordService.updateEventMessage(event);
        return event;
    }

    public List<Event> getPlannedEvents() {
        return eventRepository.findByState(EventState.PLANNED);
    }

    @Scheduled(fixedDelay = 1, timeUnit = HOURS)
    public void deleteEventSchedule() {
        for (Event event : eventRepository.findAll()) {
            deleteEvent(event);
        }
    }

    void deleteEvent(Event event) {
        if (event.getState() != EventState.ARCHIVED) {
            return;
        }
        ZonedDateTime eventExpiry = event.getDateTime().plusMonths(3);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (ZonedDateTime.now(clock).isAfter(eventExpiry)) {
            try {
                discordService.deleteEventChannel(event);
                event.setState(EventState.DELETED);
                eventRepository.save(event);
                log.info("Event {} has been deleted", eventName);
            } catch (Exception e) {
                log.error("Error deleting event {}", eventName, e);
            }

        }
    }

    @Scheduled(fixedRate = 1000 * 60)
    public void archiveEventSchedule() {
        for (Event event : eventRepository.findAll()) {
            archiveEvent(event);
        }
    }

    @Scheduled(fixedRate = 1000 * 60)
    public void notifyBeforeEventSchedule() {
        for (Event event : eventRepository.findAll()) {
            boolean isMoreThan2HoursAway = ZonedDateTime.now(clock).isBefore(
                    event.getDateTime().minus(2, HOURS.toChronoUnit())
            );
            if (!event.getState().equals(EventState.PLANNED) || isMoreThan2HoursAway) {
                return;
            }
            try {
                notifyEventRoles.executeRunnable(() -> discordService.notifyUsersBeforeEventStarts(event));
            } catch (RequestNotPermitted e) {
                log.warn("Notification to event role currently rate limited. {}", e.getMessage());
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void updateEventsOnStartup() {
        for (Event event : eventRepository.findAll()) {
            discordService.updateEventMessage(event);
        }
    }

    void archiveEvent(Event event) {
        if (event.getState() != EventState.PLANNED) {
            return;
        }
        ZonedDateTime eventExpiry = event.getDateTime().plusDays(1)
                .withHour(22).withMinute(0).withSecond(0).withNano(0);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (ZonedDateTime.now(clock).isAfter(eventExpiry)) {
            try {
                discordService.archiveEventChannel(event);
                event.setState(EventState.ARCHIVED);
                eventRepository.save(event);
                log.info("Event {} has been archived", eventName);
            } catch (Exception e) {
                log.error("Error archiving event {}", eventName, e);
            }

        }
    }
}
