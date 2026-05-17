package dev.tylercash.event.tfnsw;

import java.util.Set;

/**
 * One noteworthy alert or event surfaced by {@link TfnswNoteworthyFilter}.
 * Carries enough info to render a Discord bullet (title + url + line names
 * via {@link LineNames#distinctNames(Set)}) and to participate in the
 * snapshot hash that detects material changes between checks.
 */
public record NoteworthyItem(
        Source source, String id, String title, String detail, String url, Reason reason, Set<String> routeIds) {

    public enum Source {
        RAIL_METRO,
        TRAFFIC
    }

    public enum Reason {
        NEAREST_STATION,
        MAJOR_STATION,
        CITYWIDE_LINE,
        MAJOR_EVENT_NEAR_VENUE,
        CLOSURE_NEAR_VENUE
    }
}
