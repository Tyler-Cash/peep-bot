package dev.tylercash.event.global;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
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
     * Bridges the logback {@code OpenTelemetryAppender} (declared in {@code logback-spring.xml}) to
     * the OpenTelemetry SDK so log records are shipped over OTLP. The appender is inert until
     * {@link io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender#install(OpenTelemetry)
     * install} hands it the SDK; it buffers early records and replays them on install.
     *
     * <p>The OTel Spring Boot starter used to do this at {@code ApplicationReadyEvent}. Removing the
     * starter (commit 8db6113) dropped the install with it, silently killing OTLP log shipping while
     * the Micrometer-bridge trace/metric paths kept working. This replicates the starter's wiring
     * (same event, same timing) — see {@code OpenTelemetryAppenderAutoConfiguration.LogbackAppenderConfig}.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> otelLogbackAppenderInstaller(OpenTelemetry openTelemetry) {
        return event ->
                io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender.install(openTelemetry);
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
