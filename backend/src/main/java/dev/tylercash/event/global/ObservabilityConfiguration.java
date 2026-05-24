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
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
     * Auto-instruments every public {@code @Service} method as a span (see
     * {@link ServiceMethodObservationAspect}). On by default; set
     * {@code dev.tylercash.observability.instrument-services=false} to disable without a redeploy.
     */
    @Bean
    @ConditionalOnProperty(name = "dev.tylercash.observability.instrument-services", matchIfMissing = true)
    ServiceMethodObservationAspect serviceMethodObservationAspect(ObservationRegistry observationRegistry) {
        return new ServiceMethodObservationAspect(observationRegistry);
    }

    /**
     * Bridges the logback {@code OpenTelemetryAppender} (declared in {@code logback-spring.xml})
     * to the OpenTelemetry SDK so log records ship over OTLP. The appender is inert until
     * {@link OpenTelemetryAppender#install(OpenTelemetry)} hands it the SDK; it buffers early
     * records and replays them on install.
     *
     * <p>Boot 4's {@code spring-boot-opentelemetry} module provides the {@code SdkLoggerProvider}
     * but deliberately does not install a logback bridge — that wiring stays user-owned. Without
     * this listener the appender silently drops every log line.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> otelLogbackAppenderInstaller(OpenTelemetry openTelemetry) {
        return event -> OpenTelemetryAppender.install(openTelemetry);
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
