package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.event.model.EventState;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachineFactory
public class EventStateMachineConfig extends EnumStateMachineConfigurerAdapter<EventState, EventStateMachineEvent> {

    private final EventStateMachineGuards guards;
    private final EventStateMachineActions actions;

    public EventStateMachineConfig(EventStateMachineGuards guards, EventStateMachineActions actions) {
        this.guards = guards;
        this.actions = actions;
    }

    @Override
    public void configure(StateMachineStateConfigurer<EventState, EventStateMachineEvent> states) throws Exception {
        states.withStates()
                .initial(EventState.PLANNED)
                .states(EnumSet.allOf(EventState.class))
                .end(EventState.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<EventState, EventStateMachineEvent> transitions) throws Exception {
        transitions
                .withExternal()
                    .source(EventState.PLANNED).target(EventState.NOTIFIED)
                    .event(EventStateMachineEvent.PRE_EVENT_NOTIFY)
                    .guard(guards.preEventNotifyGuard())
                    .action(actions.preEventNotifyAction())
                    .and()
                .withExternal()
                    .source(EventState.NOTIFIED).target(EventState.ALBUM_POSTED)
                    .event(EventStateMachineEvent.POST_ALBUM)
                    .guard(guards.postAlbumGuard())
                    .action(actions.postAlbumAction())
                    .and()
                .withExternal()
                    .source(EventState.NOTIFIED).target(EventState.COMPLETED)
                    .event(EventStateMachineEvent.COMPLETE)
                    .guard(guards.completeFromNotifiedGuard())
                    .action(actions.completeAction())
                    .and()
                .withExternal()
                    .source(EventState.ALBUM_POSTED).target(EventState.COMPLETED)
                    .event(EventStateMachineEvent.COMPLETE)
                    .guard(guards.completeGuard())
                    .action(actions.completeAction())
                    .and()
                .withExternal()
                    .source(EventState.COMPLETED).target(EventState.ARCHIVED)
                    .event(EventStateMachineEvent.ARCHIVE)
                    .guard(guards.archiveGuard())
                    .action(actions.archiveAction())
                    .and()
                .withExternal()
                    .source(EventState.ARCHIVED).target(EventState.DELETED)
                    .event(EventStateMachineEvent.DELETE)
                    .guard(guards.deleteGuard())
                    .action(actions.deleteAction());
    }
}
