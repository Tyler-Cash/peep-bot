package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.immich.ImmichService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
@RequiredArgsConstructor
public class EventStateMachineActions {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ImmichService immichService;
    private final Clock clock;

    public Action<EventState, EventStateMachineEvent> preEventNotifyAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Sending pre-event notification for: {}", event.getName());
            discordService.sendMessageBeforeEvent(event);
            event.setState(EventState.NOTIFIED);
            eventRepository.save(event);
        };
    }

    public Action<EventState, EventStateMachineEvent> postAlbumAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);

            if (event.getImmichAlbumId() == null) {
                immichService.createAlbum(event.getName(), event.getDescription())
                        .ifPresent(event::setImmichAlbumId);
            }

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
            event.setState(EventState.ALBUM_POSTED);
            eventRepository.save(event);
        };
    }

    public Action<EventState, EventStateMachineEvent> completeAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Completing event (removing buttons): {}", event.getName());
            discordService.removeEventButtons(event);
            event.getNotifications().add(new Notification(
                    NotificationType.ATTENDANCE_LOCKED,
                    ZonedDateTime.now(clock).toInstant(),
                    0
            ));
            event.setState(EventState.COMPLETED);
            eventRepository.save(event);
        };
    }

    public Action<EventState, EventStateMachineEvent> cancelAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Cancelling event: {}", event.getName());
            event.setName("[CANCELLED] " + event.getName());
            discordService.removeEventButtons(event);
            discordService.updateEventMessage(event);
            discordService.updateChannelName(event);
            event.getNotifications().add(new Notification(
                    NotificationType.ATTENDANCE_LOCKED,
                    ZonedDateTime.now(clock).toInstant(),
                    0
            ));
            event.setState(EventState.COMPLETED);
            eventRepository.save(event);
        };
    }

    public Action<EventState, EventStateMachineEvent> archiveAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            String eventName = DiscordUtil.getChannelNameFromEvent(event);
            log.info("Archiving event: {}", eventName);
            discordService.archiveEventChannel(event);
            event.setState(EventState.ARCHIVED);
            eventRepository.save(event);
        };
    }

    public Action<EventState, EventStateMachineEvent> deleteAction() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            String eventName = DiscordUtil.getChannelNameFromEvent(event);
            log.info("Deleting event: {}", eventName);
            discordService.deleteEventChannel(event);
            event.setState(EventState.DELETED);
            eventRepository.save(event);
        };
    }
}
