package dev.tylercash.event.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Redirects OAuth login failures back to the frontend's /login page with a
 * short error code and the request's MDC requestId, so the user lands on a
 * branded error banner instead of Spring's default {@code /login?error}
 * (which our REST entry point rejects with a 401).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final FrontendConfiguration frontendConfiguration;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String shortCode = shortCode(exception);
        String requestId = MDC.get("requestId");
        log.warn(
                "OAuth login failed (code={}, requestId={}): {}",
                shortCode,
                requestId,
                exception.getMessage(),
                exception);

        StringBuilder url = new StringBuilder(frontendConfiguration.getUrl());
        if (!url.toString().endsWith("/")) {
            url.append('/');
        }
        url.append("login?error=").append(URLEncoder.encode(shortCode, StandardCharsets.UTF_8));
        if (requestId != null && !requestId.isBlank()) {
            url.append("&cid=").append(URLEncoder.encode(requestId, StandardCharsets.UTF_8));
        }
        response.sendRedirect(url.toString());
    }

    private static String shortCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth) {
            String code = oauth.getError() != null ? oauth.getError().getErrorCode() : null;
            if (code != null && !code.isBlank()) {
                return code;
            }
        }
        return "oauth_unknown";
    }
}
