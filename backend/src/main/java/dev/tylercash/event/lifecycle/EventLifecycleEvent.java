package dev.tylercash.event.lifecycle;

import java.util.UUID;

public sealed interface EventLifecycleEvent {
    UUID eventId();

    // Lifecycle progression (emitted by the listener that completed the prior step)
    record EventCreated(UUID eventId) implements EventLifecycleEvent {}

    record EventChannelReady(UUID eventId) implements EventLifecycleEvent {}

    record EventRolesReady(UUID eventId) implements EventLifecycleEvent {}

    record EventClassified(UUID eventId) implements EventLifecycleEvent {}

    record EventPlanned(UUID eventId) implements EventLifecycleEvent {}

    record EventPreNotified(UUID eventId) implements EventLifecycleEvent {}

    record EventCompleted(UUID eventId) implements EventLifecycleEvent {}

    record EventArchived(UUID eventId) implements EventLifecycleEvent {}

    // Branching paths
    record EventCancelRequested(UUID eventId) implements EventLifecycleEvent {}

    record EventCancelled(UUID eventId) implements EventLifecycleEvent {}

    record EventDeleteRequested(UUID eventId) implements EventLifecycleEvent {}

    record EventDeleted(UUID eventId) implements EventLifecycleEvent {}

    // Time-based (emitted by EventTickScheduler when an event crosses a window boundary)
    record EventPreNotifyDue(UUID eventId) implements EventLifecycleEvent {}

    record EventCompletionDue(UUID eventId) implements EventLifecycleEvent {}

    record EventArchivalDue(UUID eventId) implements EventLifecycleEvent {}
}
