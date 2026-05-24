package dev.tylercash.event.global;

import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;

/**
 * Auto-instruments every public method of every Spring Data {@link Repository} with an Observation,
 * so a trace shows the logical repository operation (e.g.
 * {@code GuildMemberRepository#findByGuildIdAndSnowflake}) as the parent of the physical
 * {@code datasource-micrometer} SQL spans. This adds a code-level grouping layer above the raw
 * {@code query} spans and also captures repository calls served without a DB round-trip (e.g. a
 * cache hit). See {@link AbstractMethodObservationAspect} for the shared span shape.
 *
 * <p>The pointcut targets any bean implementing {@link Repository} (covering interfaces annotated
 * {@code @Repository} as well as the Spring-Data-only ones that aren't), which makes the
 * auto-proxy creator wrap the Spring Data proxy with our advice. Methods or classes already
 * carrying {@code @Observed} are skipped. Gated by
 * {@code dev.tylercash.observability.instrument-repositories} (default on); this is the
 * highest-volume layer, so flip the flag if span volume becomes a problem.
 */
@Aspect
public class RepositoryMethodObservationAspect extends AbstractMethodObservationAspect {

    static final String OBSERVATION_NAME = "repository.method";

    public RepositoryMethodObservationAspect(ObservationRegistry registry) {
        super(registry, OBSERVATION_NAME);
    }

    @Around("this(org.springframework.data.repository.Repository)"
            + " && execution(public * *(..))"
            + " && !@annotation(io.micrometer.observation.annotation.Observed)"
            + " && !@within(io.micrometer.observation.annotation.Observed)")
    public Object aroundRepository(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint);
    }

    /**
     * For inherited CRUD methods (e.g. {@code save}, {@code findById}) the declaring type is a
     * Spring Data base interface like {@code CrudRepository}, which is useless in a trace. Resolve
     * the concrete user repository interface ({@code GuildMemberRepository}) from the proxy instead,
     * falling back to the declaring type for anything unexpected.
     */
    @Override
    String resolveClassName(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        Class<?> userInterface = resolveRepositoryInterface(joinPoint.getThis());
        return userInterface != null
                ? userInterface.getSimpleName()
                : signature.getDeclaringType().getSimpleName();
    }

    private static Class<?> resolveRepositoryInterface(Object proxy) {
        if (proxy == null) {
            return null;
        }
        Class<?> best = null;
        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(proxy.getClass())) {
            if (Repository.class.isAssignableFrom(iface) && !iface.getName().startsWith("org.springframework.data")) {
                // Prefer the most specific (sub-)interface when more than one matches.
                if (best == null || best.isAssignableFrom(iface)) {
                    best = iface;
                }
            }
        }
        return best;
    }
}
