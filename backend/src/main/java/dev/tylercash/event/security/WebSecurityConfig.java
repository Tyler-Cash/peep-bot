package dev.tylercash.event.security;

import dev.tylercash.event.security.oauth2.CustomOAuth2UserService;
import dev.tylercash.event.security.oauth2.RedirectToFrontendAfterAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.jdbc.config.annotation.web.http.JdbcHttpSessionConfiguration;

import java.time.Duration;

@Configuration
@EnableJdbcHttpSession
@RequiredArgsConstructor
public class WebSecurityConfig {
    private final CustomOAuth2UserService customOAuth2UserService;
    private final RedirectToFrontendAfterAuth redirectToFrontendAfterAuth;
    private final JdbcHttpSessionConfiguration jdbcHttpSessionConfiguration;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement((session) -> session
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new AntPathRequestMatcher("auth/is-logged-in")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("swagger-ui.html")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("swagger-ui/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("v3/api-docs/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/actuator/health")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/actuator/prometheus")).permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        .successHandler(redirectToFrontendAfterAuth)
                );
        return http.build();
    }

    @Bean
    @Primary
    public JdbcIndexedSessionRepository jdbcIndexedSessionRepository() {
        jdbcHttpSessionConfiguration.setMaxInactiveInterval(Duration.ofDays(30));
        return jdbcHttpSessionConfiguration.sessionRepository();
    }
}
