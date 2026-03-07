package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class EventLifecyclePoller {

    private static final Pageable PAGE = Pageable.ofSize(100);

    private final EventRepository eventRepository;
    private final EventStateMachineService stateMachineService;
    private final ObservationRegistry observationRegistry;

    @Observed(name = "lifecycle.poll")
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "eventLifecyclePoller")
    public void poll() {
        Page<Event> events = eventRepository.findAllByStateNotIn(PAGE, List.of(EventState.DELETED));
        log.info("Lifecycle poll processing {} events", events.getTotalElements());

        for (Event event : events) {
            try {
                Observation.createNotStarted("lifecycle.process-event", observationRegistry)
                        .lowCardinalityKeyValue("event.state", event.getState().name())
                        .observe(() -> processEvent(event));
            } catch (Exception e) {
                log.error("Error processing event lifecycle for '{}'", event.getName(), e);
            }
        }
    }

    void processEvent(Event event) {
        for (EventStateMachineEvent signal : signalsForState(event.getState())) {
            if (stateMachineService.attemptTransition(event, signal)) {
                break;
            }
        }
    }

    List<EventStateMachineEvent> signalsForState(EventState state) {
        return switch (state) {
            case PLANNED -> List.of(EventStateMachineEvent.PRE_EVENT_NOTIFY);
            case NOTIFIED -> List.of(EventStateMachineEvent.POST_ALBUM, EventStateMachineEvent.COMPLETE);
            case ALBUM_POSTED -> List.of(EventStateMachineEvent.COMPLETE);
            case COMPLETED -> List.of(EventStateMachineEvent.ARCHIVE);
            case ARCHIVED -> List.of(EventStateMachineEvent.DELETE);
            case DELETED -> List.of();
        };
    }
}
