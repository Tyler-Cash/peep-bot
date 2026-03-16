package dev.tylercash.event.global;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfiguration {

    private static final List<String> MDC_KEYS = List.of("userId", "requestId", "eventId");

    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
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
