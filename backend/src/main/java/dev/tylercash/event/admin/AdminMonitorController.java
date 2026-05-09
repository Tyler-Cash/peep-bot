package dev.tylercash.event.admin;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.lifecycle.ListenerInvocation;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.lifecycle.ListenerInvocationStatus;
import dev.tylercash.event.security.BotAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only admin endpoints powering the Overview / Jobs / activity-feed sections of the admin UI.
 * Mutations live in {@link AdminController} and {@link AdminLifecycleController}; this one is a
 * pure dashboard backend.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Bot admin · monitor", description = "Read-only dashboard endpoints for the admin panel")
public class AdminMonitorController {

    private final BotAdminService botAdminService;
    private final JDA jda;
    private final DataSource dataSource;
    private final EventRepository events;
    private final ListenerInvocationRepository invocations;
    private final AdminJobCatalog jobCatalog;

    @GetMapping("/health")
    public AdminHealthDto health(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        Map<String, AdminHealthDto.Component> components = new LinkedHashMap<>();
        components.put("bot", botStatus());
        components.put("discord", discordStatus());
        components.put("database", databaseStatus());
        components.put("scheduler", schedulerStatus());
        components.put("listenerOutbox", outboxStatus());
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return new AdminHealthDto(components, uptimeSeconds, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
    }

    @GetMapping("/jobs")
    public List<AdminJobDto> jobs(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        return jobCatalog.snapshot();
    }

    @GetMapping("/activity")
    public List<AdminActivityDto> activity(
            @RequestParam(value = "guildId", required = false) String guildId,
            @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        List<ListenerInvocation> rows;
        if (guildId != null && !guildId.isBlank()) {
            try {
                long id = Long.parseLong(guildId);
                rows = invocations.findRecentForGuild(id, org.springframework.data.domain.PageRequest.of(0, 50));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid guildId");
            }
        } else {
            rows = invocations.findTop50ByOrderByUpdatedAtDesc();
        }
        Map<UUID, Event> eventCache = new HashMap<>();
        return rows.stream().map(row -> toActivity(row, eventCache)).toList();
    }

    private AdminActivityDto toActivity(ListenerInvocation row, Map<UUID, Event> cache) {
        Event ev = cache.computeIfAbsent(
                row.getEventId(), id -> events.findById(id).orElse(null));
        String guildId = ev != null ? String.valueOf(ev.getServerId()) : null;
        String text = row.getLifecycleEventType() + " · " + row.getListenerName();
        String detail;
        String kind;
        switch (row.getStatus()) {
            case SUCCESS -> {
                detail = ev != null ? ev.getName() : "—";
                kind = "ok";
            }
            case FAILED -> {
                detail = (ev != null ? ev.getName() + " · " : "")
                        + Optional.ofNullable(row.getLastError()).orElse("");
                kind = "fail";
            }
            default -> {
                detail = ev != null ? ev.getName() : "—";
                kind = "warn";
            }
        }
        Instant ts = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
        return new AdminActivityDto(
                ts,
                kind,
                text,
                detail,
                row.getEventId(),
                guildId,
                row.getLifecycleEventType(),
                row.getListenerName(),
                row.getAttempts());
    }

    private AdminHealthDto.Component botStatus() {
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMb = rt.maxMemory() / 1024 / 1024;
        return new AdminHealthDto.Component(
                "ok", "uptime " + formatDuration(uptime) + " · heap " + usedMb + "/" + maxMb + " MB");
    }

    private AdminHealthDto.Component discordStatus() {
        try {
            JDA.Status status = jda.getStatus();
            int guilds = jda.getGuilds().size();
            String label = status.name().toLowerCase().replace('_', ' ');
            String state =
                    switch (status) {
                        case CONNECTED -> "ok";
                        case ATTEMPTING_TO_RECONNECT,
                                RECONNECT_QUEUED,
                                AWAITING_LOGIN_CONFIRMATION,
                                LOGGING_IN -> "warn";
                        default -> "fail";
                    };
            return new AdminHealthDto.Component(state, label + " · " + guilds + " guild(s)");
        } catch (Exception e) {
            return new AdminHealthDto.Component("fail", "JDA unavailable");
        }
    }

    private AdminHealthDto.Component databaseStatus() {
        try (Connection c = dataSource.getConnection()) {
            boolean ok = c.isValid(2);
            return new AdminHealthDto.Component(ok ? "ok" : "fail", ok ? "postgres reachable" : "connection invalid");
        } catch (Exception e) {
            return new AdminHealthDto.Component("fail", e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }

    private AdminHealthDto.Component schedulerStatus() {
        List<AdminJobDto> snapshot = jobCatalog.snapshot();
        long withLastRun = snapshot.stream().filter(j -> j.lastRun() != null).count();
        long total =
                snapshot.stream().filter(j -> !"(reactive)".equals(j.cron())).count();
        return new AdminHealthDto.Component("ok", total + " cron jobs · " + withLastRun + " observed in shedlock");
    }

    private AdminHealthDto.Component outboxStatus() {
        long failed = invocations.countByStatus(ListenerInvocationStatus.FAILED);
        long stuck = invocations.countStuck(24);
        String state = stuck > 0 ? "fail" : failed > 0 ? "warn" : "ok";
        return new AdminHealthDto.Component(state, failed + " failed · " + stuck + " stuck (>24 attempts)");
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private void requireBotAdmin(OAuth2User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String snowflake = principal.getAttribute("id");
        if (!botAdminService.isBotAdmin(snowflake)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bot admin access required");
        }
    }
}
