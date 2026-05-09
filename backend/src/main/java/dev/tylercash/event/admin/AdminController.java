package dev.tylercash.event.admin;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildCommandSyncService;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.global.EventCreationToggle;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.security.BotAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Bot admin", description = "Operator endpoints for managing per-guild features")
public class AdminController {

    private static final List<EventState> TERMINAL_STATES =
            List.of(EventState.POST_COMPLETED, EventState.ARCHIVED, EventState.CANCELLED, EventState.DELETED);

    private final BotAdminService botAdminService;
    private final GuildRepository guildRepository;
    private final EventRepository eventRepository;
    private final ListenerInvocationRepository listenerInvocations;
    private final JDA jda;
    private final EventCreationToggle eventCreationToggle;
    private final GuildCommandSyncService guildCommandSyncService;

    @GetMapping("/guilds")
    public List<AdminGuildDto> listGuilds(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        return guildRepository.findAll().stream().map(g -> toDto(g, since24h)).toList();
    }

    private AdminGuildDto toDto(Guild row, Instant failuresSince) {
        long id = row.getGuildId();
        var jdaGuild = jda.getGuildById(id);
        String name = jdaGuild != null ? jdaGuild.getName() : null;
        Integer memberCount = jdaGuild != null ? jdaGuild.getMemberCount() : null;
        int total = eventRepository.countByServerId(id);
        int upcoming = eventRepository.countByServerIdAndStateNotIn(id, TERMINAL_STATES);
        int failing = (int) listenerInvocations.countFailedForGuildSince(id, failuresSince);
        return new AdminGuildDto(
                String.valueOf(id),
                name,
                row.isActive(),
                row.isImmichEnabled(),
                row.isGoogleAutocompleteEnabled(),
                row.isRewindEnabled(),
                row.isContractsEnabled(),
                memberCount,
                row.getSeparatorChannel(),
                row.getPrimaryLocationName(),
                upcoming,
                total,
                failing);
    }

    @PatchMapping("/guilds/{guildId}/features")
    public AdminGuildDto updateFeatures(
            @PathVariable String guildId,
            @RequestBody AdminFeaturesRequest request,
            @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        long id = Long.parseLong(guildId);
        Guild row = guildRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guild not found"));
        boolean oldImmich = row.isImmichEnabled();
        boolean oldGoogle = row.isGoogleAutocompleteEnabled();
        boolean oldRewind = row.isRewindEnabled();
        boolean oldContracts = row.isContractsEnabled();
        if (request.immichEnabled() != null) row.setImmichEnabled(request.immichEnabled());
        if (request.googleAutocompleteEnabled() != null)
            row.setGoogleAutocompleteEnabled(request.googleAutocompleteEnabled());
        if (request.rewindEnabled() != null) row.setRewindEnabled(request.rewindEnabled());
        if (request.contractsEnabled() != null) row.setContractsEnabled(request.contractsEnabled());
        guildRepository.save(row);
        String snowflake = principal.getAttribute("id");
        if (request.immichEnabled() != null && oldImmich != row.isImmichEnabled()) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} IMMICH {} -> {}",
                    snowflake,
                    id,
                    oldImmich,
                    row.isImmichEnabled());
        }
        if (request.googleAutocompleteEnabled() != null && oldGoogle != row.isGoogleAutocompleteEnabled()) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} GOOGLE_AUTOCOMPLETE {} -> {}",
                    snowflake,
                    id,
                    oldGoogle,
                    row.isGoogleAutocompleteEnabled());
        }
        if (request.rewindEnabled() != null && oldRewind != row.isRewindEnabled()) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} REWIND {} -> {}",
                    snowflake,
                    id,
                    oldRewind,
                    row.isRewindEnabled());
        }
        if (request.contractsEnabled() != null && oldContracts != row.isContractsEnabled()) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} CONTRACTS {} -> {}",
                    snowflake,
                    id,
                    oldContracts,
                    row.isContractsEnabled());
        }
        var jdaGuild = jda.getGuildById(id);
        if (request.contractsEnabled() != null && oldContracts != row.isContractsEnabled() && jdaGuild != null) {
            guildCommandSyncService.syncCommands(jdaGuild);
        }
        return toDto(row, Instant.now().minus(24, ChronoUnit.HOURS));
    }

    @GetMapping("/event-creation")
    public Map<String, Boolean> getEventCreationState(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        return Map.of("enabled", eventCreationToggle.isEnabled());
    }

    @PostMapping("/event-creation/enable")
    public ResponseEntity<Void> enableEventCreation(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        eventCreationToggle.enable();
        log.info("AUDIT bot-admin {} ENABLED event creation", (String) principal.getAttribute("id"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/event-creation/disable")
    public ResponseEntity<Void> disableEventCreation(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        eventCreationToggle.disable();
        log.info("AUDIT bot-admin {} DISABLED event creation", (String) principal.getAttribute("id"));
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
