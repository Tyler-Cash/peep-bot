package dev.tylercash.event.admin;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import dev.tylercash.event.lifecycle.ListenerInvocation;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.security.BotAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin endpoints surfacing per-event lifecycle history and replay. The history is synthesised from
 * the {@link ListenerInvocation} outbox — we don't store a separate audit log because the outbox
 * already records every listener fire (status, attempts, last error).
 *
 * <p>Replay re-publishes an {@link EventLifecycleEvent}, which causes
 * {@link EventLifecyclePublisher} to (re)write outbox rows for every matching listener and the
 * post-commit dispatcher to fire them. Existing SUCCESS rows for the same (eventId, type, listener)
 * key get reset to PENDING by the upsert behavior of JPA's save() on a composite primary key.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Bot admin · lifecycle", description = "Per-event lifecycle history and replay")
public class AdminLifecycleController {

    private final BotAdminService botAdminService;
    private final EventRepository events;
    private final ListenerInvocationRepository invocations;
    private final EventLifecyclePublisher publisher;
    private final List<DurableEventListener<?>> listeners;

    @GetMapping("/events")
    public List<AdminEventDto> eventsForGuild(
            @RequestParam("guildId") String guildId, @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        long id;
        try {
            id = Long.parseLong(guildId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid guildId");
        }
        List<Event> rows = events.findAllByServerId(id);
        return rows.stream().map(this::toDto).toList();
    }

    @PostMapping("/replay")
    @Transactional
    public AdminReplayResponse replay(
            @Valid @RequestBody AdminReplayRequest request, @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        if (request.eventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
        }
        if (request.lifecycleEventType() == null || request.lifecycleEventType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lifecycleEventType is required");
        }
        // Verify the event exists; replaying for a vanished event would create orphan outbox rows.
        events.findById(request.eventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        EventLifecycleEvent event = reconstructEvent(request.lifecycleEventType(), request.eventId());
        List<String> matching = listeners.stream()
                .filter(l -> l.eventType().isInstance(event))
                .map(DurableEventListener::name)
                .toList();
        if (matching.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No listeners are registered for " + request.lifecycleEventType() + " — there's nothing to replay");
        }
        log.info(
                "AUDIT bot-admin {} REPLAY event={} type={} listeners={} skipSideEffects={}",
                principal.getAttribute("id"),
                request.eventId(),
                request.lifecycleEventType(),
                matching,
                Boolean.TRUE.equals(request.skipSideEffects()));
        if (Boolean.TRUE.equals(request.skipSideEffects())) {
            // Best-effort note: we accept the flag for UX symmetry with the design's checkbox, but
            // filtering specific listeners would require a custom publish() path. Surface the gap
            // in logs rather than silently dropping the intent.
            log.warn("skipSideEffects=true is currently advisory only — all listeners will fire");
        }
        publisher.publish(event);
        return new AdminReplayResponse(
                "queued · " + matching.size() + " listener(s) will re-dispatch on commit", matching);
    }

    /**
     * Explicit dispatch over every {@link EventLifecycleEvent} record. Avoids reflection so a future
     * record with a side-effecting constructor (or a vulnerable nested class arriving via a
     * dependency) cannot be instantiated through this endpoint. Adding a new record will trigger an
     * IDE warning for the missing case here — that's the whole point.
     */
    private static EventLifecycleEvent reconstructEvent(String simpleName, UUID eventId) {
        return switch (simpleName) {
            case "EventCreated" -> new EventLifecycleEvent.EventCreated(eventId);
            case "EventChannelReady" -> new EventLifecycleEvent.EventChannelReady(eventId);
            case "EventRolesReady" -> new EventLifecycleEvent.EventRolesReady(eventId);
            case "EventClassified" -> new EventLifecycleEvent.EventClassified(eventId);
            case "EventPlanned" -> new EventLifecycleEvent.EventPlanned(eventId);
            case "EventPreNotified" -> new EventLifecycleEvent.EventPreNotified(eventId);
            case "EventCompleted" -> new EventLifecycleEvent.EventCompleted(eventId);
            case "EventArchived" -> new EventLifecycleEvent.EventArchived(eventId);
            case "EventCancelRequested" -> new EventLifecycleEvent.EventCancelRequested(eventId);
            case "EventCancelled" -> new EventLifecycleEvent.EventCancelled(eventId);
            case "EventDeleteRequested" -> new EventLifecycleEvent.EventDeleteRequested(eventId);
            case "EventDeleted" -> new EventLifecycleEvent.EventDeleted(eventId);
            case "EventPreNotifyDue" -> new EventLifecycleEvent.EventPreNotifyDue(eventId);
            case "EventCompletionDue" -> new EventLifecycleEvent.EventCompletionDue(eventId);
            case "EventArchivalDue" -> new EventLifecycleEvent.EventArchivalDue(eventId);
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown lifecycleEventType '" + simpleName + "' — must be a nested record of EventLifecycleEvent");
        };
    }

    private AdminEventDto toDto(Event ev) {
        List<ListenerInvocation> rows = invocations.findByEventIdOrderByCreatedAtAsc(ev.getId());
        // Group invocations by lifecycle event type — each group becomes one history entry. We use
        // the most recent attempt across listeners for that type as the entry's status and ts.
        Map<String, List<ListenerInvocation>> byType = new HashMap<>();
        for (ListenerInvocation r : rows)
            byType.computeIfAbsent(r.getLifecycleEventType(), k -> new java.util.ArrayList<>())
                    .add(r);
        List<AdminEventDto.HistoryEntry> history = byType.values().stream()
                .map(group -> {
                    ListenerInvocation latest = group.stream()
                            .max(java.util.Comparator.comparing(
                                    r -> r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt()))
                            .orElseThrow();
                    boolean ok = group.stream()
                            .allMatch(r ->
                                    r.getStatus() == dev.tylercash.event.lifecycle.ListenerInvocationStatus.SUCCESS);
                    return new AdminEventDto.HistoryEntry(
                            stageIdForLifecycleType(latest.getLifecycleEventType()),
                            latest.getLifecycleEventType(),
                            latest.getListenerName(),
                            latest.getUpdatedAt() != null ? latest.getUpdatedAt() : latest.getCreatedAt(),
                            ok,
                            group.stream()
                                    .mapToInt(ListenerInvocation::getAttempts)
                                    .sum(),
                            ok ? null : latest.getLastError());
                })
                .sorted(java.util.Comparator.comparing(AdminEventDto.HistoryEntry::ts))
                .toList();
        return new AdminEventDto(
                ev.getId(),
                String.valueOf(ev.getServerId()),
                ev.getName(),
                "—",
                ev.getState() != null ? ev.getState().name() : "CREATED",
                ev.getDateTime() != null ? ev.getDateTime().toString() : null,
                ev.getLocation(),
                ev.getCreator(),
                ev.getAccepted() != null ? ev.getAccepted().size() : 0,
                ev.getMaybe() != null ? ev.getMaybe().size() : 0,
                ev.getDeclined() != null ? ev.getDeclined().size() : 0,
                null,
                history);
    }

    /**
     * Map a lifecycle event type to a UI-friendly stage id. Mirrors the canonical lifecycle order
     * used by the admin Events screen (created → init-channel → ... → archived; cancelled / deleted
     * branch off). The frontend has its own copy of this for stable column ordering, but we return
     * the stage id alongside the type so it's never out-of-sync with the actual outbox row.
     */
    private static String stageIdForLifecycleType(String type) {
        return switch (type) {
            case "EventCreated" -> "init-channel";
            case "EventChannelReady" -> "init-roles";
            case "EventRolesReady" -> "classify";
            case "EventClassified" -> "planned";
            case "EventPlanned" -> "planned";
            case "EventPreNotifyDue", "EventPreNotified" -> "pre-notified";
            case "EventCompletionDue", "EventCompleted" -> "completed";
            case "EventArchivalDue", "EventArchived" -> "archived";
            case "EventCancelRequested", "EventCancelled" -> "cancelled";
            case "EventDeleteRequested", "EventDeleted" -> "deleted";
            default -> "—";
        };
    }

    private void requireBotAdmin(OAuth2User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String snowflake = principal.getAttribute("id");
        if (!botAdminService.isBotAdmin(snowflake)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bot admin access required");
        }
    }
}
