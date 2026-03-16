package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStateMachineService {

    private final StateMachineFactory<EventState, EventStateMachineEvent> stateMachineFactory;

    @Observed(name = "statemachine.attempt-transition")
    public boolean attemptTransition(Event event, EventStateMachineEvent signal) {
        MDC.put("eventId", event.getId().toString());
        log.info(
                "Attempting transition for event '{}' signal={} currentState={}",
                event.getName(),
                signal,
                event.getState());
        StateMachine<EventState, EventStateMachineEvent> sm = stateMachineFactory.getStateMachine();
        sm.stopReactively().block();

        sm.getStateMachineAccessor().doWithAllRegions(accessor -> accessor.resetStateMachineReactively(
                        new DefaultStateMachineContext<>(event.getState(), null, null, null))
                .block());

        sm.getExtendedState().getVariables().put("event", event);
        sm.startReactively().block();

        EventState stateBefore = event.getState();
        sm.sendEvent(Mono.just(MessageBuilder.withPayload(signal).build())).blockLast();

        EventState stateAfter = event.getState();
        sm.stopReactively().block();

        boolean transitioned = !stateBefore.equals(stateAfter);
        log.info(
                "Transition result for event '{}': before={} after={} transitioned={}",
                event.getName(),
                stateBefore,
                stateAfter,
                transitioned);
        return transitioned;
    }
}
