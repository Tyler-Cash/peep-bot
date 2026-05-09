package dev.tylercash.event.admin;

import java.util.UUID;

/**
 * Replay an existing event through one specific lifecycle stage. {@code lifecycleEventType} must
 * match a record name nested in {@link dev.tylercash.event.lifecycle.EventLifecycleEvent}
 * (e.g. {@code "EventCreated"}, {@code "EventChannelReady"}). {@code skipSideEffects} is accepted
 * for forward-compat with the design's UI checkbox; the current implementation does not honour it.
 */
public record AdminReplayRequest(UUID eventId, String lifecycleEventType, Boolean skipSideEffects) {}
