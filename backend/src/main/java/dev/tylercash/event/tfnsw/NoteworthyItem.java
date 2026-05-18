package dev.tylercash.event.tfnsw;

import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Cause;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One noteworthy alert or event surfaced by {@link TfnswNoteworthyFilter}.
 * Carries enough info to render a Discord bullet (title + url + line names
 * via {@link LineNames#distinctNames(Set)}), to participate in the snapshot
 * hash that detects material changes between checks, and to cluster
 * downstream-of-the-same-job alerts via {@code (cause, endTimes)}.
 *
 * <p>{@code startTimes} and {@code endTimes} are parallel lists indexed by
 * active-period. For traffic items {@code cause} is {@code null}.
 */
public record NoteworthyItem(
        Source source,
        String id,
        String title,
        String detail,
        String url,
        Reason reason,
        Set<String> routeIds,
        Cause cause,
        List<Instant> startTimes,
        List<Instant> endTimes) {

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
