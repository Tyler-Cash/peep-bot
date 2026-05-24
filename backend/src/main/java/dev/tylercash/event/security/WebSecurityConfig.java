package dev.tylercash.event.security;

import dev.tylercash.event.security.dev.DevAutoLoginFilter;
import dev.tylercash.event.security.oauth2.CustomOAuth2UserService;
import dev.tylercash.event.security.oauth2.OAuth2LoginFailureHandler;
import dev.tylercash.event.security.oauth2.RedirectToFrontendAfterAuth;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.jdbc.config.annotation.web.http.JdbcHttpSessionConfiguration;

@Configuration
// cleanupCron = "-" disables Spring Session's private cleanup scheduler. That scheduler runs
// outside any observation, so its DELETE FROM SPRING_SESSION produced orphan root traces in
// Tempo. Cleanup is re-driven from SessionCleanupJob (an @Observed @Scheduled bean) instead.
@EnableJdbcHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
@RequiredArgsConstructor
public class WebSecurityConfig {
    private final CustomOAuth2UserService customOAuth2UserService;
    private final RedirectToFrontendAfterAuth redirectToFrontendAfterAuth;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final JdbcHttpSessionConfiguration jdbcHttpSessionConfiguration;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RateLimitFilter rateLimitFilter;
    private final org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

    @Autowired(required = false)
    private DevAutoLoginFilter devAutoLoginFilter;

    @Value("${dev.tylercash.openapi.public:false}")
    private boolean openapiPublic;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(session ->
                        session.sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession))
                .headers(headers -> headers.contentSecurityPolicy(csp ->
                                csp.policyDirectives("default-src 'self'; frame-ancestors 'none'; base-uri 'none'"))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(p -> p.policy("interest-cohort=()")))
                .exceptionHandling(
                        exceptionHandling -> exceptionHandling.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/auth/is-logged-in").permitAll();
                    if (openapiPublic) {
                        authorize.requestMatchers("/swagger-ui.html").permitAll();
                        authorize.requestMatchers("/swagger-ui/**").permitAll();
                        authorize.requestMatchers("/v3/api-docs/**").permitAll();
                    }
                    authorize
                            .requestMatchers("/actuator/health")
                            .permitAll()
                            .requestMatchers("/actuator/prometheus")
                            .permitAll()
                            .requestMatchers("/install-url")
                            .permitAll()
                            .anyRequest()
                            .authenticated();
                })
                .logout(logout -> logout.logoutUrl("/auth/logout")
                        .logoutSuccessHandler(
                                (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK))
                        .deleteCookies("SESSION")
                        .invalidateHttpSession(true))
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .addFilterAfter(rateLimitFilter, AnonymousAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .oauth2Login(
                        oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                                .successHandler(redirectToFrontendAfterAuth)
                                .failureHandler(oAuth2LoginFailureHandler));
        if (devAutoLoginFilter != null) {
            http.addFilterBefore(devAutoLoginFilter, AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public JdbcIndexedSessionRepository jdbcIndexedSessionRepository() {
        jdbcHttpSessionConfiguration.setMaxInactiveInterval(Duration.ofDays(30));
        return jdbcHttpSessionConfiguration.sessionRepository();
    }

    /**
     * Wraps the JDBC repository with {@link AnonymousSkippingSessionRepository} so unauthenticated
     * requests cannot fill {@code SPRING_SESSION} (pentest finding F-002). The wrapper is the
     * primary {@code SessionRepository} bean that {@code SessionRepositoryFilter} resolves; the
     * underlying {@link JdbcIndexedSessionRepository} stays in the context as the cleanup target
     * for {@link SessionCleanupJob} (its own cleanup cron is disabled — see the class annotation).
     */
    @Bean
    @Primary
    public FindByIndexNameSessionRepository<Session> anonymousSkippingSessionRepository(
            @org.springframework.beans.factory.annotation.Qualifier("jdbcIndexedSessionRepository")
                    JdbcIndexedSessionRepository delegate) {
        return new AnonymousSkippingSessionRepository(delegate);
    }
}
