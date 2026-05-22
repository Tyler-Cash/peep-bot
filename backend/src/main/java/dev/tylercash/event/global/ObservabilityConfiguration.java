package dev.tylercash.event.global;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfiguration {

    private static final List<String> MDC_KEYS =
            List.of("userId", "requestId", "eventId", "guildId", "channelId", "interactionId");

    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Bind every configured {@code resilience4j.circuitbreaker.instances.*} to the shared
     * {@link MeterRegistry}. Without this, breakers tick state/transitions internally but
     * emit no {@code resilience4j_circuitbreaker_*} meters, leaving any dashboard panel
     * that depends on them blank.
     */
    @Bean
    InitializingBean bindCircuitBreakerMetrics(CircuitBreakerRegistry breakerRegistry, MeterRegistry meterRegistry) {
        return () -> TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakerRegistry)
                .bindTo(meterRegistry);
    }

    @Bean
    ObservationHandler<Observation.Context> mdcToSpanHandler() {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                for (String key : MDC_KEYS) {
                    String value = MDC.get(key);
                    if (value != null) {
                        context.addHighCardinalityKeyValue(KeyValue.of(key, value));
                    }
                }
            }
        };
    }
}
