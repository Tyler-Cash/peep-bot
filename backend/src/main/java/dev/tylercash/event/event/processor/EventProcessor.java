package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@AllArgsConstructor
public abstract class EventProcessor {
    static final Pageable pageable = Pageable.ofSize(100);
    final DiscordService discordService;
    final EventRepository eventRepository;

    /**
     * @return Entities that are needed to be processed
     */
    public abstract Page<Event> getEvents();

    public void processAll() {
        Page<Event> events = getEvents();
        for (Event event : events) {
            try {
                processEvent(event);
            } catch (Exception e) {
                log.error("Error while processing event {}", event.getName(), e);
            }
        }
    }

    /**
     * Perform necessary action on event
     *
     * @param event
     */
    public abstract void processEvent(Event event);
}
