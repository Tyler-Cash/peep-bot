package dev.tylercash.event.admin;

import java.util.List;

/** Confirmation of a queued replay; lists the listener names that will be re-dispatched. */
public record AdminReplayResponse(String message, List<String> listeners) {}
