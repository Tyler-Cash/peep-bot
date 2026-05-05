package dev.tylercash.event.lifecycle.admin;

import dev.tylercash.event.lifecycle.ListenerInvocationId;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.lifecycle.ListenerInvocationStatus;
import dev.tylercash.event.security.BotAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/admin/listener-invocations")
@RequiredArgsConstructor
@Tag(name = "Listener invocations", description = "Operator endpoints for the lifecycle outbox")
public class ListenerInvocationAdminController {

    private final ListenerInvocationRepository repo;
    private final BotAdminService botAdminService;

    @PostMapping("/{eventId}/{type}/{listenerName}/retry-now")
    public ResponseEntity<Void> retryNow(
            @PathVariable UUID eventId,
            @PathVariable String type,
            @PathVariable String listenerName,
            @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        var id = new ListenerInvocationId(eventId, type, listenerName);
        return repo.findById(id)
                .map(row -> {
                    row.setStatus(ListenerInvocationStatus.PENDING);
                    row.setNextRetryAt(Instant.now());
                    repo.save(row);
                    log.info(
                            "AUDIT bot-admin {} retry-now listener-invocation event={} type={} listener={}",
                            principal.getAttribute("id"),
                            eventId,
                            type,
                            listenerName);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{eventId}/{type}/{listenerName}")
    public ResponseEntity<Void> abandon(
            @PathVariable UUID eventId,
            @PathVariable String type,
            @PathVariable String listenerName,
            @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        var id = new ListenerInvocationId(eventId, type, listenerName);
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        log.info(
                "AUDIT bot-admin {} abandoned listener-invocation event={} type={} listener={}",
                principal.getAttribute("id"),
                eventId,
                type,
                listenerName);
        return ResponseEntity.noContent().build();
    }

    private void requireBotAdmin(OAuth2User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String snowflake = principal.getAttribute("id");
        if (!botAdminService.isBotAdmin(snowflake)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bot admin access required");
        }
    }
}
