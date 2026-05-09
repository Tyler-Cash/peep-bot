package dev.tylercash.event.admin;

import java.util.Map;

/**
 * Health summary for the admin overview screen. Each component reports a status (ok / warn / fail)
 * and a one-line detail string. {@code components} keys are stable identifiers the UI uses to look
 * up labels (bot, discord, database, scheduler, listenerOutbox).
 */
public record AdminHealthDto(Map<String, Component> components, long uptimeSeconds, String syncedAt) {

    public record Component(String status, String detail) {}
}
