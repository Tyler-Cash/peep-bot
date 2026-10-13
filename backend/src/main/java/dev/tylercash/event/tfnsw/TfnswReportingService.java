package dev.tylercash.event.tfnsw;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Cause;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Renders TfNSW noteworthy items as a single plain-content Discord message per
 * event. Week-before deltas are sent as a reply to the original post; if the
 * original message is missing, the caller is expected to fall back to a fresh
 * {@link #post(Event, List)}.
 *
 * <p>TfNSW publishes one alert per line for the same piece of work, so items
 * with an identical headline are first merged into a single bullet listing
 * every affected line.
 *
 * <p>Rail/metro items that share both a GTFS {@code cause} (e.g. MAINTENANCE)
 * and an identical sorted list of active-period end-times are bucketed under a
 * single parent banner — the end-time matches because every downstream alert
 * for a planned job inherits the scheduled return-to-service. This collapses
 * the typical "5 lines of City Circle trackwork rephrased" output into one
 * banner with sub-bullets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswReportingService {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final String FIRST_HEADER =
            "🚧 Transport notice — trackwork or disruption may affect travel to this event:";
    /** Line names shown before a headline before the rest collapse into "+N more". */
    private static final int MAX_LINE_NAMES = 3;

    private static final String UPDATE_HEADER = "⚠️ Update — additional disruption since the previous notice:";

    private final DiscordService discordService;

    /** Returns the new message snowflake, or null if nothing was posted. */
    public Long post(Event event, List<NoteworthyItem> items) {
        if (items.isEmpty()) return null;
        String body = FIRST_HEADER + "\n" + formatBody(items);
        try {
            return discordService.sendContentToEventChannel(event, body);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW notice to event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Pins the notice so it stays prominent in the run-up to the event. Best
     * effort — pinning is a UX nicety, so any failure is logged and swallowed
     * rather than aborting the surrounding snapshot save.
     */
    public void pin(Event event, long messageId) {
        try {
            discordService.pinMessageInEventChannel(event, messageId);
        } catch (Exception e) {
            log.warn("Failed to pin TfNSW notice {} for event {}: {}", messageId, event.getId(), e.getMessage());
        }
    }

    /** Returns true if the reply succeeded; false if the parent message could not be resolved. */
    public Boolean postUpdate(Event event, long originalMessageId, List<NoteworthyItem> newItems) {
        if (newItems.isEmpty()) return true;
        String body = UPDATE_HEADER + "\n" + formatBody(newItems);
        try {
            return discordService.replyToMessage(event, originalMessageId, body);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW update to event {}: {}", event.getId(), e.getMessage());
            return false;
        }
    }

    private static String formatBody(List<NoteworthyItem> items) {
        // Preserve original ordering of clusters by first-occurrence of each key.
        Map<String, List<NoteworthyItem>> clusters = new LinkedHashMap<>();
        for (NoteworthyItem item : mergeSameHeadline(items)) {
            clusters.computeIfAbsent(clusterKey(item), k -> new ArrayList<>()).add(item);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (List<NoteworthyItem> cluster : clusters.values()) {
            if (!first) sb.append('\n');
            first = false;
            if (cluster.size() >= 2) {
                sb.append(renderCluster(cluster));
            } else {
                sb.append(renderBullet(cluster.get(0)));
            }
        }
        return sb.toString();
    }

    /**
     * Collapses items carrying the same headline (the same work, reported once
     * per affected line) into one item covering every line and active period.
     * The per-line descriptions usually differ only in their end time, so when
     * they disagree the merged item takes its timing from the combined periods.
     */
    static List<NoteworthyItem> mergeSameHeadline(List<NoteworthyItem> items) {
        Map<String, List<NoteworthyItem>> groups = new LinkedHashMap<>();
        for (NoteworthyItem item : items) {
            String headline = item.title() == null ? "" : item.title().strip();
            String key = headline.isEmpty()
                    ? "solo:" + item.id()
                    : item.source() + ":" + headline.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<NoteworthyItem> out = new ArrayList<>();
        for (List<NoteworthyItem> group : groups.values()) {
            out.add(group.size() == 1 ? group.get(0) : merge(group));
        }
        return out;
    }

    private static NoteworthyItem merge(List<NoteworthyItem> group) {
        NoteworthyItem first = group.get(0);
        Set<String> routeIds = new TreeSet<>();
        Set<String> timings = new LinkedHashSet<>();
        String url = null;
        List<Instant> starts = new ArrayList<>();
        List<Instant> ends = new ArrayList<>();
        Set<String> seenPeriods = new HashSet<>();
        for (NoteworthyItem item : group) {
            routeIds.addAll(item.routeIds());
            timings.add(String.valueOf(summariseTiming(item.detail(), item.title())));
            if (url == null && item.url() != null && !item.url().isBlank()) url = item.url();
            for (int i = 0; i < item.startTimes().size(); i++) {
                Instant start = item.startTimes().get(i);
                Instant end = item.endTimes().get(i);
                if (seenPeriods.add(start + "/" + end)) {
                    starts.add(start);
                    ends.add(end);
                }
            }
        }
        String detail = timings.size() == 1 || starts.isEmpty()
                ? first.detail()
                : clusterTimeWindow(List.of(withPeriods(first, starts, ends)));
        return new NoteworthyItem(
                first.source(),
                first.id(),
                first.title(),
                detail,
                url,
                first.reason(),
                routeIds,
                first.cause(),
                starts,
                ends);
    }

    private static NoteworthyItem withPeriods(NoteworthyItem item, List<Instant> starts, List<Instant> ends) {
        return new NoteworthyItem(
                item.source(),
                item.id(),
                item.title(),
                item.detail(),
                item.url(),
                item.reason(),
                item.routeIds(),
                item.cause(),
                starts,
                ends);
    }

    /**
     * Bucket key. Rail items with a non-UNKNOWN cause cluster by
     * {@code cause + sorted end-time epoch seconds}; everything else is
     * forced into its own bucket via the unique alert id.
     */
    static String clusterKey(NoteworthyItem item) {
        if (item.source() != NoteworthyItem.Source.RAIL_METRO
                || item.cause() == null
                || item.cause() == Cause.UNKNOWN) {
            return "solo:" + item.id();
        }
        StringBuilder sb = new StringBuilder(item.cause().name());
        for (Instant end : new TreeSet<>(item.endTimes())) sb.append(':').append(end.getEpochSecond());
        return sb.toString();
    }

    private static String renderCluster(List<NoteworthyItem> cluster) {
        Cause cause = cluster.get(0).cause();
        StringBuilder sb = new StringBuilder("🚧 ")
                .append(causeLabel(cause))
                .append(" — ")
                .append(clusterTimeWindow(cluster))
                .append(" (")
                .append(cluster.size())
                .append(" alerts):");
        for (NoteworthyItem item : cluster) {
            sb.append("\n  ↳ ").append(renderSubBullet(item));
        }
        return sb.toString();
    }

    private static String renderBullet(NoteworthyItem item) {
        StringBuilder sb = new StringBuilder("• ").append(renderHeadline(item));
        String timing = summariseTiming(item.detail(), item.title());
        if (timing != null) sb.append("\n  ").append(timing);
        return sb.toString();
    }

    /** Cluster children skip the per-alert time subline — the parent banner already shows it. */
    private static String renderSubBullet(NoteworthyItem item) {
        return renderHeadline(item);
    }

    private static String renderHeadline(NoteworthyItem item) {
        String headline = item.title() == null ? "" : item.title().strip();
        StringBuilder sb = new StringBuilder();
        String lines = lineLabel(item, headline);
        if (lines != null) sb.append(lines).append(" — ");
        sb.append(headline);
        if (item.url() != null && !item.url().isBlank()) {
            sb.append(" ([details](").append(item.url()).append("))");
        }
        return sb.toString();
    }

    /**
     * Affected line names to prefix the headline with, or null when there are
     * none or the headline already names its line.
     */
    private static String lineLabel(NoteworthyItem item, String headline) {
        List<String> names = List.copyOf(LineNames.distinctNames(item.routeIds()));
        if (names.isEmpty()) return null;
        for (String name : names) {
            if (headlineNamesLine(headline, name)) return null;
        }
        if (names.size() <= MAX_LINE_NAMES) return String.join(", ", names);
        return String.join(", ", names.subList(0, MAX_LINE_NAMES)) + " +" + (names.size() - MAX_LINE_NAMES) + " more";
    }

    /**
     * True if the alert headline already begins with a line identifier (e.g.
     * "T1 Western Line: ...", "Sydney Metro: ...") or with the human line label
     * we'd otherwise prepend. Used to avoid the duplicated "Western Line — T1
     * Western Line: ..." prefix.
     */
    static boolean headlineNamesLine(String headline, String lineLabel) {
        if (headline == null || headline.isEmpty()) return false;
        String lower = headline.toLowerCase();
        if (lower.matches("^t\\d+\\b.*")) return true;
        if (lower.startsWith("sydney metro")) return true;
        if (lineLabel != null && lower.startsWith(lineLabel.toLowerCase())) return true;
        return false;
    }

    /**
     * Pulls the date + time window out of a GTFS-R alert description. TfNSW
     * descriptions consistently lead with a date line ("Monday 18 and Tuesday
     * 19 May") followed by a sentence describing the active window ("Nightly
     * from 9:40PM to 1:30AM, ..."). Both are useful context; the rest tends
     * to be boilerplate ("Trackwork on the City Circle may also affect...").
     * The window sentence often ends by restating the headline ("From 3:30AM to
     * 8:00AM, buses replace trains between Penrith and St Marys"); that tail is
     * dropped since the bullet already carries it.
     */
    static String summariseTiming(String detail, String headline) {
        if (detail == null) return null;
        String dateLine = null;
        String timeLine = null;
        for (String raw : detail.split("\n")) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            if (dateLine == null) {
                dateLine = s;
                continue;
            }
            int dot = s.indexOf('.');
            timeLine = stripHeadlineTail((dot > 0 ? s.substring(0, dot) : s).strip(), headline);
            break;
        }
        if (dateLine == null) return null;
        String joined = timeLine == null ? dateLine : dateLine + " — " + timeLine;
        if (joined.length() > 220) joined = joined.substring(0, 217) + "…";
        return joined;
    }

    private static String stripHeadlineTail(String timeLine, String headline) {
        if (headline == null || headline.isBlank()) return timeLine;
        String tail = ", " + headline.strip().toLowerCase(Locale.ROOT);
        if (timeLine.toLowerCase(Locale.ROOT).endsWith(tail)) {
            return timeLine.substring(0, timeLine.length() - tail.length()).strip();
        }
        return timeLine;
    }

    /**
     * Banner time window for a cluster, e.g. "Mon 18 / Tue 19 May, 9:20PM–1:30AM".
     * Dates are labelled by the Sydney-local night each period starts (overnight
     * trackwork reads as the night it begins, not the next morning). Open-ended
     * periods — those with no genuine end, where TfNSW defaulted the end to ~a
     * year out — contribute their start but not their end, so the window renders
     * "from 9:40PM" rather than a synthetic end time.
     */
    static String clusterTimeWindow(List<NoteworthyItem> cluster) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        LocalTime earliestStart = null;
        LocalTime latestEnd = null;
        for (NoteworthyItem item : cluster) {
            List<Instant> starts = item.startTimes();
            List<Instant> ends = item.endTimes();
            for (int i = 0; i < starts.size(); i++) {
                var startZdt = starts.get(i).atZone(SYDNEY);
                dates.add(startZdt.toLocalDate());
                LocalTime st = startZdt.toLocalTime();
                if (earliestStart == null || st.isBefore(earliestStart)) earliestStart = st;
                if (!isOpenEnded(starts.get(i), ends.get(i))) {
                    LocalTime et = ends.get(i).atZone(SYDNEY).toLocalTime();
                    if (latestEnd == null || et.isAfter(latestEnd)) latestEnd = et;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean firstDate = true;
        for (LocalDate d : dates) {
            if (!firstDate) sb.append(" / ");
            firstDate = false;
            sb.append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .append(' ')
                    .append(d.getDayOfMonth());
        }
        if (!dates.isEmpty()) {
            sb.append(' ').append(dates.last().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        }
        if (earliestStart != null) {
            sb.append(", ");
            if (latestEnd != null) {
                sb.append(formatTimeOfDay(earliestStart)).append('–').append(formatTimeOfDay(latestEnd));
            } else {
                sb.append("from ").append(formatTimeOfDay(earliestStart));
            }
        }
        return sb.toString();
    }

    /**
     * A period with no genuine end — TfNSW defaults a missing GTFS end time to
     * roughly a year out, so any period running more than 180 days is treated as
     * open-ended for rendering. Real trackwork windows are hours or, at most, a
     * handful of days.
     */
    private static boolean isOpenEnded(Instant start, Instant end) {
        return end.isAfter(start.plus(Duration.ofDays(180)));
    }

    /** Twelve-hour clock, no leading zero on hour, lowercase am/pm dropped in favour of AM/PM. */
    private static String formatTimeOfDay(LocalTime t) {
        int hour = t.getHour();
        int min = t.getMinute();
        String suffix = hour < 12 ? "AM" : "PM";
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        return min == 0 ? h12 + suffix : String.format("%d:%02d%s", h12, min, suffix);
    }

    private static String causeLabel(Cause c) {
        if (c == null) return "Disruption";
        return switch (c) {
            case MAINTENANCE -> "Trackwork";
            case CONSTRUCTION -> "Construction";
            case ACCIDENT -> "Accident";
            case STRIKE -> "Industrial action";
            case WEATHER -> "Weather disruption";
            case TECHNICAL_PROBLEM -> "Technical issue";
            case MEDICAL_EMERGENCY -> "Medical emergency";
            case POLICE_ACTIVITY -> "Police incident";
            case HOLIDAY, DEMONSTRATION, OTHER, UNKNOWN -> "Disruption";
        };
    }
}
