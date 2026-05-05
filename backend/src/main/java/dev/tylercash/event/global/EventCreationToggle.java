package dev.tylercash.event.global;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EventCreationToggle {
    private final AtomicBoolean enabled;

    public EventCreationToggle(@Value("${dev.tylercash.event-creation.enabled:true}") boolean initial) {
        this.enabled = new AtomicBoolean(initial);
    }

    @PostConstruct
    void logState() {
        log.info("Event creation is {}", enabled.get() ? "ENABLED" : "DISABLED");
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void enable() {
        if (enabled.compareAndSet(false, true)) {
            log.info("Event creation ENABLED");
        }
    }

    public void disable() {
        if (enabled.compareAndSet(true, false)) {
            log.info("Event creation DISABLED");
        }
    }
}
