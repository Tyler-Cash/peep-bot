package dev.tylercash.event.security;

import dev.tylercash.event.security.dev.DevAutoLoginFilter;
import dev.tylercash.event.security.oauth2.CustomOAuth2UserService;
import dev.tylercash.event.security.oauth2.RedirectToFrontendAfterAuth;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.jdbc.config.annotation.web.http.JdbcHttpSessionConfiguration;

@Configuration
@EnableJdbcHttpSession
@RequiredArgsConstructor
public class WebSecurityConfig {
    private final CustomOAuth2UserService customOAuth2UserService;
    private final RedirectToFrontendAfterAuth redirectToFrontendAfterAuth;
    private final JdbcHttpSessionConfiguration jdbcHttpSessionConfiguration;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RateLimitFilter rateLimitFilter;
    private final org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

    @Autowired(required = false)
    private DevAutoLoginFilter devAutoLoginFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(session ->
                        session.sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession))
                .exceptionHandling(
                        exceptionHandling -> exceptionHandling.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/auth/is-logged-in")
                        .permitAll()
                        .requestMatchers("/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/actuator/health")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/avatar/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .addFilterAfter(rateLimitFilter, AnonymousAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .oauth2Login(
                        oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                                .successHandler(redirectToFrontendAfterAuth));
        if (devAutoLoginFilter != null) {
            http.addFilterBefore(devAutoLoginFilter, AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    @Primary
    public JdbcIndexedSessionRepository jdbcIndexedSessionRepository() {
        jdbcHttpSessionConfiguration.setMaxInactiveInterval(Duration.ofDays(90));
        return jdbcHttpSessionConfiguration.sessionRepository();
    }
}
