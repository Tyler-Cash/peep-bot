package dev.tylercash.event.global;

import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.security.UserInfoDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/auth")
@Tag(name = "Authentication", description = "Authentication status operations")
public class SecurityController {
    private final DiscordService discordService;
    private final DiscordAuthService discordAuthService;
    private final GuildRepository guildRepository;

    @Operation(summary = "Check authentication status", description = "Returns current user info if authenticated")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User is authenticated"),
        @ApiResponse(responseCode = "401", description = "User is not authenticated")
    })
    @GetMapping("/is-logged-in")
    public UserInfoDto isLoggedIn(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not logged in");
        }
        String userSnowflake = principal.getAttribute("id");
        String username = principal.getAttribute("username");
        String globalName = principal.getAttribute("global_name");
        String displayName = globalName != null ? globalName : username;
        long userId = Long.parseLong(userSnowflake);
        List<dev.tylercash.event.discord.Guild> activeGuilds = guildRepository.findAllByActiveTrue();
        List<String> organiserGuildIds = activeGuilds.stream()
                .map(Guild::getGuildId)
                .filter(id -> discordService.isUserOrganiserOfServer(id, userId))
                .map(String::valueOf)
                .toList();
        List<String> ownedGuildIds = activeGuilds.stream()
                .map(Guild::getGuildId)
                .filter(id -> discordAuthService.isGuildOwner(id, userId))
                .map(String::valueOf)
                .toList();
        return new UserInfoDto(
                username, displayName, userSnowflake, organiserGuildIds, ownedGuildIds, "/api/avatar/" + userSnowflake);
    }
}
