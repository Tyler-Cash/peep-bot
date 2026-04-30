package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/guild/{guildId}/settings")
@RequiredArgsConstructor
@Tag(name = "Guild", description = "Discord guild info")
public class GuildSettingsController {

    private final GuildSettingsRepository settingsRepository;
    private final GuildMembershipService guildMembershipService;
    private final DiscordService discordService;

    @GetMapping
    public GuildSettingsDto getSettings(@PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        return settingsRepository
                .findById(guildIdLong)
                .map(s -> new GuildSettingsDto(
                        s.getPrimaryLocationPlaceId(),
                        s.getPrimaryLocationName(),
                        s.getPrimaryLocationLat(),
                        s.getPrimaryLocationLng()))
                .orElse(new GuildSettingsDto(null, null, null, null));
    }

    @PatchMapping
    public GuildSettingsDto updateSettings(
            @PathVariable String guildId,
            @RequestBody GuildSettingsRequest request,
            @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        boolean isAdmin = discordService.isUserAdminOfServer(guildIdLong, Long.parseLong(snowflake));
        if (!isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        GuildSettings settings =
                settingsRepository.findById(guildIdLong).orElse(new GuildSettings(guildIdLong, null, null, null, null));
        settings.setPrimaryLocationPlaceId(request.primaryLocationPlaceId());
        settings.setPrimaryLocationName(request.primaryLocationName());
        settings.setPrimaryLocationLat(request.primaryLocationLat());
        settings.setPrimaryLocationLng(request.primaryLocationLng());
        settingsRepository.save(settings);
        return new GuildSettingsDto(
                settings.getPrimaryLocationPlaceId(),
                settings.getPrimaryLocationName(),
                settings.getPrimaryLocationLat(),
                settings.getPrimaryLocationLng());
    }
}
