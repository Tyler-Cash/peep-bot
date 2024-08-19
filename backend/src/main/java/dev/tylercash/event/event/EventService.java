package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.HOURS;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final Clock clock;

    public String createEvent(Event event) {
        ServerTextChannel channel = discordService.createEventChannel(event);
        Message message;
        try {
            message = discordService.postEventMessage(event, channel);
            event.setServerId(channel.getServer().getId());
            event.setChannelId(channel.getId());
            event.setMessageId(message.getId());
            eventRepository.save(event);
        } catch (Exception e) {
            channel.delete();
            throw e;
        }
        discordService.pinMessage(message);
        discordService.sortChannels(discordService.getEventCategory());
        return "Created event for " + event.getName();
    }

    public Event getEvent(String id) {
        return eventRepository.findById(UUID.fromString(id)).get();
    }

    public Event updateEvent(Event event) {
        discordService.updateEventMessage(event);
        return event;
    }

    public List<Event> getEvents() {
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
        LocalDateTime eventExpiry = event.getDateTime().plusMonths(3);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (LocalDateTime.now(clock).isAfter(eventExpiry)) {
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

    @Scheduled(fixedDelay = 1, timeUnit = HOURS)
    public void archiveEventSchedule() {
        for (Event event : eventRepository.findAll()) {
            archiveEvent(event);
        }
    }

    void archiveEvent(Event event) {
        if (event.getState() != EventState.PLANNED) {
            return;
        }
        LocalDateTime eventExpiry = event.getDateTime().plusDays(1)
                .withHour(22).withMinute(0).withSecond(0).withNano(0);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (LocalDateTime.now(clock).isAfter(eventExpiry)) {
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
