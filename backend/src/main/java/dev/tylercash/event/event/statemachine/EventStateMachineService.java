package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventStateMachineService {

    private final StateMachineFactory<EventState, EventStateMachineEvent> stateMachineFactory;

    public boolean attemptTransition(Event event, EventStateMachineEvent signal) {
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

        return !stateBefore.equals(stateAfter);
    }
}
