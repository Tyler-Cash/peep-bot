package dev.tylercash.event.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Pure unit tests for {@link OAuth2LoginFailureHandler}. The handler's job is to translate
 * authentication failures into a frontend redirect with a short error code and the current
 * request's MDC requestId — no DB or Spring context is required. Verifies the URL composition
 * across the failure-shape matrix.
 *
 * <p>Parallel-safe: every test allocates its own mock response and clears MDC in
 * {@link #clearMdc()}.
 */
class OAuth2LoginFailureHandlerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OAuth2LoginFailureHandler handlerWithFrontend(String protocol, String hostname, String path) {
        FrontendConfiguration cfg = new FrontendConfiguration();
        cfg.setProtocol(protocol);
        cfg.setHostname(hostname);
        cfg.setPath(path);
        return new OAuth2LoginFailureHandler(cfg);
    }

    private HttpServletRequest request() {
        return Mockito.mock(HttpServletRequest.class);
    }

    @Test
    void oAuth2Exception_withErrorCode_redirectsWithThatCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response")));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://event.test.local/login?error=invalid_token_response");
    }

    @Test
    void nonOAuth2Exception_fallsBackToOauthUnknown() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");

        handler.onAuthenticationFailure(request(), response, new BadCredentialsException("bad creds"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://event.test.local/login?error=oauth_unknown");
    }

    @Test
    void mdcRequestId_isAppendedAsCidQueryParam() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");
        MDC.put("requestId", "req-abc-123");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://event.test.local/login?error=access_denied&cid=req-abc-123");
    }

    @Test
    void blankMdcRequestId_isOmitted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");
        MDC.put("requestId", "   ");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(response.getRedirectedUrl()).doesNotContain("cid=");
    }

    @Test
    void specialCharsInErrorCodeAndRequestId_areUrlEncoded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");
        MDC.put("requestId", "req with space & ampersand");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("weird code/value")));

        String url = response.getRedirectedUrl();
        // URL-encoded space is "+" or "%20" depending on encoder; URLEncoder uses + for queries.
        assertThat(url).contains("error=weird+code%2Fvalue");
        assertThat(url).contains("cid=req+with+space+%26+ampersand");
    }

    @Test
    void frontendUrlWithoutTrailingSlash_isNormalised() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // path "" → FrontendConfiguration.getUrl() returns "https://event.test.local" (no slash);
        // handler must insert one before "login?...".
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("invalid_request")));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://event.test.local/login?error=invalid_request");
    }

    @Test
    void handlerWritesRedirectStatusAndLocationHeader() throws Exception {
        // Sanity: the handler must use sendRedirect (302), not write a 200 body.
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        OAuth2LoginFailureHandler handler = handlerWithFrontend("https", "event.test.local", "/");

        handler.onAuthenticationFailure(
                request(), response, new OAuth2AuthenticationException(new OAuth2Error("server_error")));

        Mockito.verify(response).sendRedirect("https://event.test.local/login?error=server_error");
    }
}
