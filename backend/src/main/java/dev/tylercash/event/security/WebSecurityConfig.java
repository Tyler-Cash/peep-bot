//package dev.tylercash.event.security;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.config.Customizer;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.web.SecurityFilterChain;
//import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
//
//@Configuration
//public class WebSecurityConfig {
//    public static final String DISCORD_BOT_USER_AGENT = "DiscordBot (https://github.com/fourscouts/blog/tree/master/oauth2-discord)";
//
//
//    @Bean
//    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//        http
//                .oauth2Login(Customizer.withDefaults())
//                .authorizeHttpRequests(authorize -> authorize
//                        .requestMatchers(new AntPathRequestMatcher("swagger-ui.html")).permitAll()
//                        .requestMatchers(new AntPathRequestMatcher("swagger-ui/**")).permitAll()
//                        .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
//                        .requestMatchers(new AntPathRequestMatcher("v3/api-docs/**")).permitAll()
//                        .requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
//                        .anyRequest().permitAll()
//                );
//        return http.build();
//    }
//}
