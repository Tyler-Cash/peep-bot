package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import lombok.AllArgsConstructor;
import lombok.Setter;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Setter
@Component
@AllArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;

    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        if (!discordService.isUserMemberOfServer(discordConfiguration.getGuildId(), Long.parseLong(Objects.requireNonNull(oAuth2User.getAttribute("id"))))) {
            throw new OAuth2AuthenticationException("User not a member of discord server " + discordConfiguration.getGuildId());
        }
        return oAuth2User;
    }
}