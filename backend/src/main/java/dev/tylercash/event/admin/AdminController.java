package dev.tylercash.event.admin;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DirectoryEntry;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildCommandSyncService;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.global.EventCreationToggle;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.security.BotAdminService;
import dev.tylercash.event.tarot.TarotService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
    private final TarotService tarotService;

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
                row.isTfnswEnabled(),
                row.isTarotEnabled(),
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
        boolean oldTfnsw = row.isTfnswEnabled();
        boolean oldTarot = row.isTarotEnabled();
        if (request.immichEnabled() != null) row.setImmichEnabled(request.immichEnabled());
        if (request.googleAutocompleteEnabled() != null)
            row.setGoogleAutocompleteEnabled(request.googleAutocompleteEnabled());
        if (request.rewindEnabled() != null) row.setRewindEnabled(request.rewindEnabled());
        if (request.contractsEnabled() != null) row.setContractsEnabled(request.contractsEnabled());
        if (request.tfnswEnabled() != null) row.setTfnswEnabled(request.tfnswEnabled());
        if (request.tarotEnabled() != null) row.setTarotEnabled(request.tarotEnabled());
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
        if (request.tfnswEnabled() != null && oldTfnsw != row.isTfnswEnabled()) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} TFNSW {} -> {}",
                    snowflake,
                    id,
                    oldTfnsw,
                    row.isTfnswEnabled());
        }
        boolean tarotChanged = request.tarotEnabled() != null && oldTarot != row.isTarotEnabled();
        if (tarotChanged) {
            log.info(
                    "AUDIT bot-admin {} flipped guild {} TAROT {} -> {}",
                    snowflake,
                    id,
                    oldTarot,
                    row.isTarotEnabled());
        }
        boolean contractsChanged = request.contractsEnabled() != null && oldContracts != row.isContractsEnabled();
        var jdaGuild = jda.getGuildById(id);
        if ((contractsChanged || tarotChanged) && jdaGuild != null) {
            guildCommandSyncService.syncCommands(jdaGuild);
        }
        return toDto(row, Instant.now().minus(24, ChronoUnit.HOURS));
    }

    @GetMapping("/guilds/{guildId}/tarot")
    public AdminTarotSettingsDto getTarotSettings(
            @PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        return tarotSettings(requireGuild(guildId).getGuildId());
    }

    @PutMapping("/guilds/{guildId}/tarot/channels")
    public AdminTarotSettingsDto updateTarotChannels(
            @PathVariable String guildId,
            @RequestBody AdminTarotChannelsRequest request,
            @AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        long id = requireGuild(guildId).getGuildId();
        List<Long> channelIds = (request.channelIds() == null ? List.<String>of() : request.channelIds())
                .stream().map(AdminController::parseSnowflake).distinct().toList();
        var jdaGuild = jda.getGuildById(id);
        if (jdaGuild != null) {
            for (long channelId : channelIds) {
                if (jdaGuild.getTextChannelById(channelId) == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Channel " + channelId + " is not a text channel in this guild");
                }
            }
        }
        tarotService.replaceAllowedChannels(id, channelIds);
        log.info(
                "AUDIT bot-admin {} set guild {} TAROT channels to {}",
                (String) principal.getAttribute("id"),
                id,
                channelIds.isEmpty() ? "all" : channelIds);
        return tarotSettings(id);
    }

    private AdminTarotSettingsDto tarotSettings(long guildId) {
        var jdaGuild = jda.getGuildById(guildId);
        List<DirectoryEntry> available = jdaGuild == null
                ? List.of()
                : jdaGuild.getTextChannels().stream()
                        .sorted(Comparator.comparingInt(c -> c.getPositionRaw()))
                        .map(c -> new DirectoryEntry(c.getId(), c.getName()))
                        .toList();
        List<String> allowed = tarotService.allowedChannelIds(guildId).stream()
                .map(String::valueOf)
                .toList();
        return new AdminTarotSettingsDto(available, allowed, tarotService.drawsSince(guildId, 7));
    }

    private Guild requireGuild(String guildId) {
        return guildRepository
                .findById(parseSnowflake(guildId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Guild not found"));
    }

    private static long parseSnowflake(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id: " + value);
        }
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
