package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private RateLimitConfiguration config;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfiguration();
        config.setReadCapacity(2);
        config.setReadRefillSeconds(60);
        config.setWriteCapacity(2);
        config.setWriteRefillSeconds(60);
        filter = new RateLimitFilter(config);
    }

    @Test
    void requestWithinLimit_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/event");
        request.setServletPath("/event");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("2");
    }

    @Test
    void requestExceedingReadLimit_returns429() throws ServletException, IOException {
        for (int i = 0; i < config.getReadCapacity(); i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/event");
            req.setServletPath("/event");
            req.setRemoteAddr("10.0.0.1");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/event");
        request.setServletPath("/event");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getContentAsString()).contains("Too many requests");
    }

    @Test
    void requestExceedingWriteLimit_returns429() throws ServletException, IOException {
        for (int i = 0; i < config.getWriteCapacity(); i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/event");
            req.setServletPath("/event");
            req.setRemoteAddr("10.0.0.2");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/event");
        request.setServletPath("/event");
        request.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void readAndWriteUseSeparateBuckets() throws ServletException, IOException {
        // Exhaust write bucket
        for (int i = 0; i < config.getWriteCapacity(); i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/event");
            req.setServletPath("/event");
            req.setRemoteAddr("10.0.0.3");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        // GET should still succeed (separate bucket)
        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/event");
        getRequest.setServletPath("/event");
        getRequest.setRemoteAddr("10.0.0.3");
        MockHttpServletResponse getResponse = new MockHttpServletResponse();

        filter.doFilterInternal(getRequest, getResponse, filterChain);

        assertThat(getResponse.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void differentUsersHaveSeparateBuckets() throws ServletException, IOException {
        // Exhaust bucket for user A
        for (int i = 0; i < config.getReadCapacity(); i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/event");
            req.setServletPath("/event");
            req.setRemoteAddr("10.0.0.10");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        // User B should still succeed
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/event");
        request.setServletPath("/event");
        request.setRemoteAddr("10.0.0.11");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void actuatorPathsAreExcluded() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        request.setRemoteAddr("127.0.0.1");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void swaggerPathsAreExcluded() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        request.setServletPath("/swagger-ui/index.html");
        request.setRemoteAddr("127.0.0.1");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void retryAfterHeaderIsCorrect() throws ServletException, IOException {
        // Exhaust bucket
        for (int i = 0; i < config.getReadCapacity(); i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/event");
            req.setServletPath("/event");
            req.setRemoteAddr("10.0.0.20");
            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
        }

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/event");
        request.setServletPath("/event");
        request.setRemoteAddr("10.0.0.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        String retryAfter = response.getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        int seconds = Integer.parseInt(retryAfter);
        assertThat(seconds).isPositive();
    }
}
