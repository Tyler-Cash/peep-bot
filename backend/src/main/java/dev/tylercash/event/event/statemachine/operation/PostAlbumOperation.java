package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichService;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class PostAlbumOperation {

    private final Clock clock;
    private final ImmichService immichService;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;

    public Guard<EventState, EventStateMachineEvent> guard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return now.isAfter(event.getDateTime().plusHours(1));
        };
    }

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            String albumUrl = immichService.getShareUrl(event.getImmichShareKey());
            log.info("Posting album link for event: {}", event.getName());
            discordService.sendAlbumLink(event, albumUrl);
            eventServiceProvider.getObject().populateAttendance(event);
            discordService.updateEventMessage(event);
            event.setState(EventState.ALBUM_POSTED);
            eventRepository.save(event);
        };
    }
}
