package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordConfiguration discordConfiguration;
    private final DiscordService discordService;
    private final EventRepository eventRepository;

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
        discordService.sortChannels();
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

    @Scheduled(fixedDelay = 5, timeUnit = MINUTES)
    public void deleteEventSchedule() {

        for (Event event : eventRepository.findAll()) {
            LocalDateTime eventExpiry = event.getDateTime().plus(3, DAYS.toChronoUnit());
            String eventName = DiscordUtil.getChannelNameFromEvent(event);
            if (LocalDateTime.now().isAfter(eventExpiry)) {
                try {
                    discordService.deleteEventChannel(event);
                    eventRepository.deleteById(event.getId());
                    log.info("Event {} has been deleted", eventName);
                } catch (Exception e) {
                    log.error("Error deleting event {}", eventName, e);
                }

            }
        }
    }
}
