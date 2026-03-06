package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.immich.ImmichConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
public class EventStateMachineGuards {

    private final Clock clock;
    private final ImmichConfiguration immichConfiguration;

    public Guard<EventState, EventStateMachineEvent> preEventNotifyGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return now.isAfter(event.getDateTime().minusHours(2))
                    && now.isBefore(event.getDateTime());
        };
    }

    public Guard<EventState, EventStateMachineEvent> postAlbumGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return immichConfiguration.isEnabled()
                    && now.isAfter(event.getDateTime().plusHours(1));
        };
    }

    public Guard<EventState, EventStateMachineEvent> completeFromNotifiedGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return !immichConfiguration.isEnabled()
                    && now.isAfter(event.getDateTime().plusHours(6));
        };
    }

    public Guard<EventState, EventStateMachineEvent> completeGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return now.isAfter(event.getDateTime().plusHours(6));
        };
    }

    public Guard<EventState, EventStateMachineEvent> archiveGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime archiveTime = event.getDateTime()
                    .plusDays(1).withHour(22).withMinute(0).withSecond(0).withNano(0);
            return now.isAfter(archiveTime);
        };
    }

    public Guard<EventState, EventStateMachineEvent> deleteGuard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return now.isAfter(event.getDateTime().plusMonths(3));
        };
    }
}
