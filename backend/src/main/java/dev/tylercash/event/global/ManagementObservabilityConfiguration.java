package dev.tylercash.event.global;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Registers {@link ServerHttpObservationFilter} on the management server context (port 9001).
 *
 * <p>Spring Boot's {@code WebMvcObservationAutoConfiguration} only registers the filter against
 * the main {@code DispatcherServlet}. The management server runs in its own child
 * {@code ApplicationContext}, so requests to {@code /actuator/*} never traversed an HTTP server
 * observation — yet they still hit the full Spring Security filter chain (with its
 * {@code ObservationFilterChainDecorator}). Every 30s docker healthcheck consequently emitted two
 * orphan root spans (`security filterchain before`, `security filterchain after`) in Tempo.
 *
 * <p>Registering the same filter under {@link ManagementContextConfiguration} attaches it to the
 * management context's servlet, so actuator hits get a proper {@code http get /actuator/health}
 * SERVER span, and the security/JDBC observations chain under it. Order matches Spring Boot's
 * default for the main context ({@code HIGHEST_PRECEDENCE + 1}) so the observation wraps every
 * downstream filter.
 */
@ManagementContextConfiguration(proxyBeanMethods = false)
public class ManagementObservabilityConfiguration {

    @Bean
    public FilterRegistrationBean<ServerHttpObservationFilter> managementServerHttpObservationFilter(
            ObservationRegistry observationRegistry) {
        FilterRegistrationBean<ServerHttpObservationFilter> registration =
                new FilterRegistrationBean<>(new ServerHttpObservationFilter(observationRegistry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("managementServerHttpObservationFilter");
        return registration;
    }
}
