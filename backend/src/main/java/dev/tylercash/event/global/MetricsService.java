package dev.tylercash.event.global;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
@Getter
public class MetricsService {
    private final Timer discordMessageComponentEventTimer;

    public MetricsService(MeterRegistry registry) {
        discordMessageComponentEventTimer = Timer.builder("discord.message_component_event_timer")
                .description("Times how long to respond to a Discord message component event")
                .register(registry);
    }
}
