package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
@AllArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;
    private DiscordUserCacheService discordUserCacheService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String snowflake = Objects.requireNonNull(oAuth2User.getAttribute("id"));
        String username = oAuth2User.getAttribute("username");
        log.info("Authenticated user {}", username);

        if (!discordService.isUserMemberOfServer(discordConfiguration.getGuildId(), Long.parseLong(snowflake))) {
            log.warn("User {} not a member of the server. id: {}", username, snowflake);
            throw new OAuth2AuthenticationException(
                    "User not a member of discord server " + discordConfiguration.getGuildId());
        }

        String avatarHash = oAuth2User.getAttribute("avatar");
        String avatarUrl = AvatarDownloadService.discordAvatarUrl(snowflake, avatarHash);
        discordUserCacheService.upsertUser(snowflake, username != null ? username : snowflake, avatarUrl);

        return oAuth2User;
    }
}
