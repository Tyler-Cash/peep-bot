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

    private final InitChannelOperation initChannel;
    private final InitRolesOperation initRoles;
    private final ClassifyOperation classify;
    private final InitCompleteOperation initComplete;
    private final PreEventNotifyOperation preEventNotify;
    private final PrepareAlbumOperation prepareAlbum;
    private final PostAlbumOperation postAlbum;
    private final CompleteOperation complete;
    private final CancelOperation cancel;
    private final ArchiveOperation archive;
    private final DeleteOperation delete;

    public EventStateMachineConfig(
            InitChannelOperation initChannel,
            InitRolesOperation initRoles,
            ClassifyOperation classify,
            InitCompleteOperation initComplete,
            PreEventNotifyOperation preEventNotify,
            PrepareAlbumOperation prepareAlbum,
            PostAlbumOperation postAlbum,
            CompleteOperation complete,
            CancelOperation cancel,
            ArchiveOperation archive,
            DeleteOperation delete) {
        this.initChannel = initChannel;
        this.initRoles = initRoles;
        this.classify = classify;
        this.initComplete = initComplete;
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
                .initial(EventState.CREATED)
                .states(EnumSet.allOf(EventState.class))
                .end(EventState.DELETED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<EventState, EventStateMachineEvent> transitions)
            throws Exception {
        transitions
                // CREATED -> INIT_CHANNEL
                .withExternal()
                .source(EventState.CREATED)
                .target(EventState.INIT_CHANNEL)
                .event(EventStateMachineEvent.INIT_CHANNEL)
                .action(initChannel.action())
                .and()
                // INIT_CHANNEL -> INIT_ROLES
                .withExternal()
                .source(EventState.INIT_CHANNEL)
                .target(EventState.INIT_ROLES)
                .event(EventStateMachineEvent.INIT_ROLES)
                .action(initRoles.action())
                .and()
                // INIT_ROLES -> CLASSIFY
                .withExternal()
                .source(EventState.INIT_ROLES)
                .target(EventState.CLASSIFY)
                .event(EventStateMachineEvent.CLASSIFY)
                .action(classify.action())
                .and()
                // CLASSIFY -> PLANNED
                .withExternal()
                .source(EventState.CLASSIFY)
                .target(EventState.PLANNED)
                .event(EventStateMachineEvent.INIT_COMPLETE)
                .action(initComplete.action())
                .and()
                // PLANNED -> PRE_NOTIFIED
                .withExternal()
                .source(EventState.PLANNED)
                .target(EventState.PRE_NOTIFIED)
                .event(EventStateMachineEvent.PRE_EVENT_NOTIFY)
                .guard(preEventNotify.guard())
                .action(preEventNotify.action())
                .and()
                // PRE_NOTIFIED -> POST_ALBUM_READY
                .withExternal()
                .source(EventState.PRE_NOTIFIED)
                .target(EventState.POST_ALBUM_READY)
                .event(EventStateMachineEvent.PREPARE_ALBUM)
                .guard(prepareAlbum.guard())
                .action(prepareAlbum.action())
                .and()
                // PRE_NOTIFIED -> POST_COMPLETED (skip album)
                .withExternal()
                .source(EventState.PRE_NOTIFIED)
                .target(EventState.POST_COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // POST_ALBUM_READY -> POST_ALBUM_SHARED
                .withExternal()
                .source(EventState.POST_ALBUM_READY)
                .target(EventState.POST_ALBUM_SHARED)
                .event(EventStateMachineEvent.POST_ALBUM)
                .guard(postAlbum.guard())
                .action(postAlbum.action())
                .and()
                // POST_ALBUM_READY -> POST_COMPLETED
                .withExternal()
                .source(EventState.POST_ALBUM_READY)
                .target(EventState.POST_COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // POST_ALBUM_SHARED -> POST_COMPLETED
                .withExternal()
                .source(EventState.POST_ALBUM_SHARED)
                .target(EventState.POST_COMPLETED)
                .event(EventStateMachineEvent.COMPLETE)
                .guard(complete.guard())
                .action(complete.action())
                .and()
                // CREATED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.CREATED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // INIT_CHANNEL -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.INIT_CHANNEL)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // INIT_ROLES -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.INIT_ROLES)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // CLASSIFY -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.CLASSIFY)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // PLANNED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.PLANNED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // PRE_NOTIFIED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.PRE_NOTIFIED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // POST_ALBUM_READY -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.POST_ALBUM_READY)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // POST_ALBUM_SHARED -> ARCHIVED (cancel)
                .withExternal()
                .source(EventState.POST_ALBUM_SHARED)
                .target(EventState.ARCHIVED)
                .event(EventStateMachineEvent.CANCEL)
                .action(cancel.action())
                .and()
                // POST_COMPLETED -> ARCHIVED
                .withExternal()
                .source(EventState.POST_COMPLETED)
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
