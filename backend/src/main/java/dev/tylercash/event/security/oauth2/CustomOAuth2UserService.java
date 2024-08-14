package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Log4j2
@Setter
@Component
@AllArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        log.info("Authenticated user {}", (String) oAuth2User.getAttribute("username"));
        if (!discordService.isUserMemberOfServer(discordConfiguration.getGuildId(), Long.parseLong(Objects.requireNonNull(oAuth2User.getAttribute("id"))))) {
            log.warn("User {} not a member of the server", (String) oAuth2User.getAttribute("username"));
            throw new OAuth2AuthenticationException("User not a member of discord server " + discordConfiguration.getGuildId());
        }
        return oAuth2User;
    }
}