package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
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
    private DiscordUserCacheService discordUserCacheService;
    private GuildRepository guildRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String snowflake = Objects.requireNonNull(oAuth2User.getAttribute("id"));
        String username = oAuth2User.getAttribute("username");
        String globalName = oAuth2User.getAttribute("global_name");
        log.info("Authenticated user {}", username);

        String avatarHash = oAuth2User.getAttribute("avatar");
        String avatarUrl = AvatarDownloadService.discordAvatarUrl(snowflake, avatarHash);
        String displayName = globalName != null ? globalName : (username != null ? username : snowflake);
        String name = username != null ? username : snowflake;
        long userId = Long.parseLong(snowflake);

        // Record membership in every active guild the bot shares with this user.
        // Users in zero guilds still log in successfully — frontend renders an
        // install CTA when GET /guild returns [].
        for (Guild row : guildRepository.findAllByActiveTrue()) {
            if (discordService.isUserMemberOfServer(row.getGuildId(), userId)) {
                discordUserCacheService.upsertUser(snowflake, displayName, name, avatarUrl, row.getGuildId());
            }
        }

        return oAuth2User;
    }
}
