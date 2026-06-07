package dev.tylercash.event.tfnsw;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PlacesDetailsClient;
import dev.tylercash.event.places.PlacesDetailsClient.Coords;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates the TfNSW noteworthy-disruption flow for a single event:
 * resolve coords, fetch alerts + traffic, filter, post if anything is
 * noteworthy, persist a snapshot for additive-update dedup on the
 * pre-event follow-up recheck.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswOrchestrator {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final TfnswConfiguration cfg;
    private final TfnswAlertsClient alertsClient;
    private final LiveTrafficClient liveTrafficClient;
    private final TfnswNoteworthyFilter filter;
    private final TfnswReportingService reporter;
    private final TfnswEventSnapshotRepository snapshots;
    private final EventRepository events;
    private final GuildRepository guilds;
    private final PlacesDetailsClient placesDetails;
    private final GtfsStopsIndex stopsIndex;
    private final LockProvider lockProvider;

    /**
     * Maximum time we hold the per-channel ShedLock. Long enough for a slow Discord
     * post + GTFS-R fetch, short enough that a dead worker can't deadlock the channel
     * for very long. The {@code lockAtLeastFor} is zero — we always release in
     * {@code finally}, so the lock disappears as soon as the work completes.
     */
    private static final Duration TFNSW_LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    @Transactional
    public void process(UUID eventId, boolean isFollowUpRecheck) {
        if (!cfg.isEnabled()) return;
        Event event = events.findById(eventId).orElse(null);
        if (event == null) return;
        Guild guild = guilds.findById(event.getServerId()).orElse(null);
        if (guild == null || !guild.isTfnswEnabled()) return;

        // Serialise per-channel: EventCreated listener and the daily follow-up poller
        // both reach this method, and the snapshot-based dedup is read-modify-write so two
        // overlapping runs against the same channel can post the same item twice.
        // Skipping when the lock is held is correct: the EventCreated path is a durable
        // listener that re-fires, and the follow-up poller runs again tomorrow.
        Long channelId = event.getChannelId();
        if (channelId == null || channelId == 0L) {
            log.debug("Skipping TfNSW for event {} — no channel set", eventId);
            return;
        }
        LockConfiguration lockCfg =
                new LockConfiguration(Instant.now(), "tfnsw:" + channelId, TFNSW_LOCK_AT_MOST_FOR, Duration.ZERO);
        Optional<SimpleLock> lock = lockProvider.lock(lockCfg);
        if (lock.isEmpty()) {
            // Throwing surfaces as a retry on the durable-listener path; the poller's
            // loop catches and logs, so it'll get another chance on the next daily run.
            throw new TfnswChannelBusyException(channelId);
        }
        try {
            processLocked(event, isFollowUpRecheck);
        } finally {
            lock.get().unlock();
        }
    }

    private void processLocked(Event event, boolean isFollowUpRecheck) {
        UUID eventId = event.getId();
        Coords coords = resolveCoords(event);
        if (coords == null) {
            log.debug("Skipping TfNSW for event {} — no coords resolvable", eventId);
            return;
        }

        var rail = new ArrayList<TfnswNoteworthyFilter.RailAlert>();
        rail.addAll(alertsClient.fetchSydneyTrains());
        rail.addAll(alertsClient.fetchSydneyMetro());
        rail.addAll(alertsClient.fetchTripReplacements());
        var trafficEvents = new ArrayList<TfnswNoteworthyFilter.TrafficEvent>();
        trafficEvents.addAll(liveTrafficClient.fetchMajorEvents());
        trafficEvents.addAll(liveTrafficClient.fetchHazards());

        LocalDate eventDate = event.getDateTime().withZoneSameInstant(SYDNEY).toLocalDate();
        String nearestStationId = stopsIndex
                .findNearest(coords.lat(), coords.lng(), cfg.getNearestStationRadiusKm())
                .map(GtfsStopsIndex.Stop::stopId)
                .orElse(null);
        List<NoteworthyItem> items =
                filter.filter(rail, trafficEvents, coords.lat(), coords.lng(), nearestStationId, eventDate);

        Optional<TfnswEventSnapshot> prior = snapshots.findById(eventId);
        TfnswEventSnapshot snap = prior.orElseGet(() -> {
            TfnswEventSnapshot s = new TfnswEventSnapshot();
            s.setEventId(eventId);
            return s;
        });
        snap.setAlertIdsHash(hash(items)); // kept for backwards visibility, no longer drives posting

        if (items.isEmpty()) {
            snapshots.save(snap);
            return;
        }

        Set<String> previouslyPosted = parsePostedIds(snap.getPostedAlertIds());
        List<NoteworthyItem> newItems =
                items.stream().filter(i -> !previouslyPosted.contains(i.id())).toList();

        // Idempotency: if we've never posted for this event, do the first-run post.
        // Otherwise (whether this is the follow-up recheck or a retried EventCreated
        // delivery from the outbox) treat it as an additive update — only post items
        // we haven't already posted, so a transient failure after Discord delivery
        // can't double-post on retry.
        if (snap.getOriginalMessageId() == null) {
            Long messageId = reporter.post(event, items);
            if (messageId != null) {
                snap.setOriginalMessageId(messageId);
                snap.setPostedAlertIds(serialisePostedIds(
                        items.stream().map(NoteworthyItem::id).toList()));
                snap.setLastPostedAt(Instant.now());
                reporter.pin(event, messageId);
            }
            snapshots.save(snap);
            return;
        }

        if (!newItems.isEmpty()) {
            boolean posted = Boolean.TRUE.equals(reporter.postUpdate(event, snap.getOriginalMessageId(), newItems));
            if (!posted) {
                // Reply target missing or never recorded — send fresh and re-anchor.
                Long messageId = reporter.post(event, newItems);
                if (messageId != null) {
                    snap.setOriginalMessageId(messageId);
                    posted = true;
                }
            }
            if (posted) {
                List<String> merged = new ArrayList<>(previouslyPosted);
                for (NoteworthyItem n : newItems) merged.add(n.id());
                snap.setPostedAlertIds(serialisePostedIds(merged));
                snap.setLastPostedAt(Instant.now());
            }
        }

        // On the pre-event follow-up, (re)pin the notice so it's prominent in the
        // run-up to the event, even when there was nothing new to post.
        if (isFollowUpRecheck && snap.getOriginalMessageId() != null) {
            reporter.pin(event, snap.getOriginalMessageId());
        }
        snapshots.save(snap);
    }

    private static Set<String> parsePostedIds(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split("\n")).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    private static String serialisePostedIds(List<String> ids) {
        return String.join("\n", ids);
    }

    private Coords resolveCoords(Event e) {
        if (e.getLocationLat() != null && e.getLocationLng() != null) {
            return new Coords(e.getLocationLat(), e.getLocationLng());
        }
        if (e.getLocationPlaceId() == null || e.getLocationPlaceId().isBlank()) return null;
        Coords fetched = placesDetails.fetchCoords(e.getLocationPlaceId()).orElse(null);
        if (fetched != null) {
            e.setLocationLat(fetched.lat());
            e.setLocationLng(fetched.lng());
            events.save(e);
        }
        return fetched;
    }

    static String hash(List<NoteworthyItem> items) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            items.stream().map(NoteworthyItem::id).sorted().forEach(s -> md.update(s.getBytes()));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "0";
        }
    }
}
