package dev.tylercash.event.global;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ServiceConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
