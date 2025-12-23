package dev.tylercash.event.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedirectToFrontendAfterAuth implements AuthenticationSuccessHandler {
    private final FrontendConfiguration frontendConfiguration;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        response.setHeader("Location", frontendConfiguration.getUrl());
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
    }
}
