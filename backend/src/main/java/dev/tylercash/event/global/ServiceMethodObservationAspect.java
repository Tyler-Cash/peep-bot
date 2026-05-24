package dev.tylercash.event.global;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Auto-instruments every public method of every {@code @Service} bean with an Observation, so a
 * trace shows the service-call tree (e.g. {@code EventService#getEvent}) without hand-annotating
 * each method with {@code @Observed}. Mirrors micrometer's {@code ObservedAspect} output: span
 * (contextual) name {@code ClassName#methodName}, low-cardinality {@code class}/{@code method} tags,
 * and a single timer named {@link #OBSERVATION_NAME}. The {@code mdcToSpanHandler} enriches these
 * spans with {@code requestId}/{@code userId}/etc. just like annotated ones, and the Pyroscope span
 * processor profiles them.
 *
 * <p>Scope is deliberate: only {@code @Service} stereotypes (the business layer), only public
 * methods, and never methods/classes already carrying {@code @Observed} — so there is exactly one
 * span per call. As with {@code @Observed}, this is Spring-AOP proxy-based: cross-bean calls are
 * advised, self-invocation within a bean is not. Gated by
 * {@code dev.tylercash.observability.instrument-services} (default on); narrow the pointcut or flip
 * the flag if span volume on a hot path becomes a problem.
 */
@Aspect
public class ServiceMethodObservationAspect {

    static final String OBSERVATION_NAME = "service.method";

    private final ObservationRegistry registry;

    public ServiceMethodObservationAspect(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Around("@within(org.springframework.stereotype.Service)"
            + " && execution(public * *(..))"
            + " && !@annotation(io.micrometer.observation.annotation.Observed)"
            + " && !@within(io.micrometer.observation.annotation.Observed)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();

        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(className + "#" + methodName)
                .lowCardinalityKeyValue("class", className)
                .lowCardinalityKeyValue("method", methodName)
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            return joinPoint.proceed();
        } catch (Throwable error) {
            observation.error(error);
            throw error;
        } finally {
            observation.stop();
        }
    }
}
