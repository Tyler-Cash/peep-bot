package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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

    public String createEvent(Event event) {
        TextChannel channel = discordService.createEventChannel(event);
        try {
            Message message = discordService.postEventMessage(event, channel);
            event.setServerId(message.getGuildIdLong());
            event.setChannelId(channel.getIdLong());
            event.setMessageId(message.getIdLong());
            eventRepository.save(event);
        } catch (Exception e) {
            channel.delete().queue();
            throw e;
        }
        discordService.sortActiveChannels();
        return "Created event for " + event.getName();
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
