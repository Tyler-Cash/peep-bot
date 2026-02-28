package dev.tylercash.event.global;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.security.UserInfoDto;
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
public class SecurityController {
    private final DiscordService discordService;
    private final DiscordConfiguration discordConfiguration;

    @GetMapping("/is-logged-in")
    public UserInfoDto isLoggedIn(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not logged in");
        }
        String userSnowflake = principal.getAttribute("id");
        String username = principal.getAttribute("username");
        boolean isAdmin = discordService.isUserAdminOfServer(
                discordConfiguration.getGuildId(), Long.parseLong(userSnowflake));
        return new UserInfoDto(username, userSnowflake, isAdmin);
    }
}
