package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
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

    @Transactional
    public Event updateEvent(Event event) {
        discordService.updateEventMessage(event);
        discordService.updateChannelName(event);
        eventRepository.save(event);
        return event;
    }

    public Page<Event> getPlannedEvents(Pageable pageable) {
        return eventRepository.findByState(pageable, EventState.PLANNED);
    }
}
