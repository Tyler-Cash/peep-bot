package dev.tylercash.event.event;

import dev.tylercash.event.db.model.Event;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import lombok.RequiredArgsConstructor;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordConfiguration discordConfiguration;
    private final DiscordService discordService;
    private final EventRepository eventRepository;

    public String createEvent(Event event) {
        ServerTextChannel channel = discordService.createEventChannel(event);
        Message message = discordService.postEventMessage(event, channel);
        event.setServerId(channel.getServer().getId());
        event.setChannelId(channel.getId());
        event.setMessageId(message.getId());
        eventRepository.save(event);
        discordService.pinMessage(message);
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
        return eventRepository.findAll();
    }
}
