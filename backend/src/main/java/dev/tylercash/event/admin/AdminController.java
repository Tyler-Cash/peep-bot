package dev.tylercash.event.admin;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.security.BotAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Bot admin", description = "Operator endpoints for managing per-guild features")
public class AdminController {

    private final BotAdminService botAdminService;
    private final GuildRepository guildRepository;
    private final JDA jda;

    @GetMapping("/guilds")
    public List<AdminGuildDto> listGuilds(@AuthenticationPrincipal OAuth2User principal) {
        requireBotAdmin(principal);
        return guildRepository.findAll().stream()
                .map(g -> {
                    var jdaGuild = jda.getGuildById(g.getGuildId());
                    String name = jdaGuild != null ? jdaGuild.getName() : null;
                    return new AdminGuildDto(
                            String.valueOf(g.getGuildId()),
                            name,
                            g.isActive(),
                            g.isImmichEnabled(),
                            g.isGoogleAutocompleteEnabled(),
                            g.isRewindEnabled());
                })
                .toList();
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
        if (request.immichEnabled() != null) row.setImmichEnabled(request.immichEnabled());
        if (request.googleAutocompleteEnabled() != null)
            row.setGoogleAutocompleteEnabled(request.googleAutocompleteEnabled());
        if (request.rewindEnabled() != null) row.setRewindEnabled(request.rewindEnabled());
        guildRepository.save(row);
        var jdaGuild = jda.getGuildById(id);
        return new AdminGuildDto(
                String.valueOf(id),
                jdaGuild != null ? jdaGuild.getName() : null,
                row.isActive(),
                row.isImmichEnabled(),
                row.isGoogleAutocompleteEnabled(),
                row.isRewindEnabled());
    }

    private void requireBotAdmin(OAuth2User principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String snowflake = principal.getAttribute("id");
        if (!botAdminService.isBotAdmin(snowflake)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bot admin access required");
        }
    }
}
