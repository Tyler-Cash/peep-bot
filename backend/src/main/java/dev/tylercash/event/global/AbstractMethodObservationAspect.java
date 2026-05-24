package dev.tylercash.event.global;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Shared machinery for the stereotype auto-instrumentation aspects
 * ({@link ServiceMethodObservationAspect}, {@link ControllerMethodObservationAspect},
 * {@link RepositoryMethodObservationAspect}). Each subclass declares a single {@code @Around}
 * advice with a stereotype-specific pointcut and delegates to {@link #observe(ProceedingJoinPoint)},
 * which opens a micrometer Observation per call mirroring micrometer's {@code ObservedAspect}
 * output: span (contextual) name {@code ClassName#methodName}, low-cardinality {@code class}/{@code
 * method} tags, and a single timer named by {@code observationName}. The {@code mdcToSpanHandler}
 * enriches these spans with {@code requestId}/{@code userId}/etc. just like annotated ones, and the
 * Pyroscope span processor profiles them.
 *
 * <p>As with {@code @Observed}, this is Spring-AOP proxy-based: cross-bean calls are advised,
 * self-invocation within a bean is not.
 */
abstract class AbstractMethodObservationAspect {

    private final ObservationRegistry registry;
    private final String observationName;

    AbstractMethodObservationAspect(ObservationRegistry registry, String observationName) {
        this.registry = registry;
        this.observationName = observationName;
    }

    final Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = resolveClassName(joinPoint, signature);
        String methodName = signature.getName();

        Observation observation = Observation.createNotStarted(observationName, registry)
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

    /**
     * Simple name used for the span/contextual name and {@code class} tag. Defaults to the method's
     * declaring type; subclasses override when the declaring type is unhelpful (e.g. repository CRUD
     * methods declared on Spring Data base interfaces).
     */
    String resolveClassName(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        return signature.getDeclaringType().getSimpleName();
    }
}
