package dev.tylercash.event.global;

import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Auto-instruments every public method of every {@code @Service} bean with an Observation, so a
 * trace shows the service-call tree (e.g. {@code EventService#getEvent}) without hand-annotating
 * each method with {@code @Observed}. See {@link AbstractMethodObservationAspect} for the shared
 * span shape.
 *
 * <p>Scope is deliberate: only {@code @Service} stereotypes (the business layer), only public
 * methods, and never methods/classes already carrying {@code @Observed} — so there is exactly one
 * span per call. Gated by {@code dev.tylercash.observability.instrument-services} (default on);
 * narrow the pointcut or flip the flag if span volume on a hot path becomes a problem.
 */
@Aspect
public class ServiceMethodObservationAspect extends AbstractMethodObservationAspect {

    static final String OBSERVATION_NAME = "service.method";

    public ServiceMethodObservationAspect(ObservationRegistry registry) {
        super(registry, OBSERVATION_NAME);
    }

    @Around("@within(org.springframework.stereotype.Service)"
            + " && execution(public * *(..))"
            + " && !@annotation(io.micrometer.observation.annotation.Observed)"
            + " && !@within(io.micrometer.observation.annotation.Observed)")
    public Object aroundService(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint);
    }
}
