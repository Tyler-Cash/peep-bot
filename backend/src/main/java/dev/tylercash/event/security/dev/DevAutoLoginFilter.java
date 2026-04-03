package dev.tylercash.event.security.dev;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DevAutoLoginFilter extends OncePerRequestFilter {
    private final DevUserProperties devUserProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !devUserProperties.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            OAuth2User mockUser = new DefaultOAuth2User(
                    List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                    Map.of("id", devUserProperties.getDiscordId(), "username", devUserProperties.getUsername()),
                    "username");
            OAuth2AuthenticationToken token =
                    new OAuth2AuthenticationToken(mockUser, mockUser.getAuthorities(), "discord");
            SecurityContextHolder.getContext().setAuthentication(token);
            log.debug(
                    "Dev auto-login: authenticated as {} ({})",
                    devUserProperties.getUsername(),
                    devUserProperties.getDiscordId());
        }
        filterChain.doFilter(request, response);
    }
}
