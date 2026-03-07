package dev.tylercash.event.event.statemachine;

import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.operation.*;
import java.util.EnumSet;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

@Configuration
@EnableStateMachineFactory
public class EventStateMachineConfig extends EnumStateMachineConfigurerAdapter<EventState, EventStateMachineEvent> {

    private final PreEventNotifyOperation preEventNotify;
    private final PrepareAlbumOperation prepareAlbum;
    private final PostAlbumOperation postAlbum;
    private final CompleteOperation complete;
    private final CancelOperation cancel;
    private final ArchiveOperation archive;
    private final DeleteOperation delete;

    public EventStateMachineConfig(
            PreEventNotifyOperation preEventNotify,
            PrepareAlbumOperation prepareAlbum,
            PostAlbumOperation postAlbum,
            CompleteOperation complete,
            CancelOperation cancel,
            ArchiveOperation archive,
            DeleteOperation delete) {
        this.preEventNotify = preEventNotify;
        this.prepareAlbum = prepareAlbum;
        this.postAlbum = postAlbum;
        this.complete = complete;
        this.cancel = cancel;
        this.archive = archive;
        this.delete = delete;
    }

    @Override
    public void configure(StateMachineStateConfigurer<EventState, EventStateMachineEvent> states) throws Exception {
        states.withStates()
                .initial(EventState.PLANNED)
                .states(EnumSet.allOf(EventState.class))
                .end(EventState.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<EventState, EventStateMachineEvent> transitions)
            throws Exception {
        transitions
                // PLANNED -> NOTIFIED
                .withExternal()
                .source(EventState.PLANNED)
                .target(EventState.NOTIFIED)
                .event(EventStateMachineEvent.PRE_EVENT_NOTIFY)
                .guard(preEventNotify.guard())
                .action(preEventNotify.action())
                .and()
                // NOTIFIED -> ALBUM_READY
                .withExternal()
                .source(EventState.NOTIFIED)
                .target(EventState.ALBUM_READY)
                .event(EventStateMachineEvent.PREPARE_ALBUM)
                .guard(prepareAlbum.guard())
                .action(prepareAlbum.action())
                .and()
                // NOTIFIED -> COMPLETED (skip album)
                .withExternal()
                .source(EventState.NOTIFIED)
                .target(EventState.COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // ALBUM_READY -> ALBUM_POSTED
                .withExternal()
                .source(EventState.ALBUM_READY)
                .target(EventState.ALBUM_POSTED)
                .event(EventStateMachineEvent.POST_ALBUM)
                .guard(postAlbum.guard())
                .action(postAlbum.action())
                .and()
                // ALBUM_READY -> COMPLETED
                .withExternal()
                .source(EventState.ALBUM_READY)
                .target(EventState.COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // ALBUM_POSTED -> COMPLETED
                .withExternal()
                .source(EventState.ALBUM_POSTED)
                .target(EventState.COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // PLANNED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.PLANNED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // NOTIFIED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.NOTIFIED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // ALBUM_READY -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.ALBUM_READY)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // ALBUM_POSTED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.ALBUM_POSTED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // COMPLETED -> ARCHIVED
                .withExternal()
                .source(EventState.COMPLETED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.ARCHIVE)
                .guard(archive.guard())
                .action(archive.action())
                .and()
                // ARCHIVED -> DELETED
                .withExternal()
                .source(EventState.ARCHIVED)
                .target(EventState.DELETED)
                .event(EventStateMachineEvent.DELETE)
                .guard(delete.guard())
                .action(delete.action());
    }
}
