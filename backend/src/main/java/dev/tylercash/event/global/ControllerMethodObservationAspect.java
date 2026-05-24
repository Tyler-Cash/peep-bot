package dev.tylercash.event.global;

import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Auto-instruments every public method of every {@code @Controller}/{@code @RestController} bean
 * with an Observation, so a trace shows which handler ran (e.g.
 * {@code AvatarController#getAvatar}) as a child of the HTTP server span. Without it, handlers that
 * call repositories directly (no intervening {@code @Service}) produce a trace that jumps straight
 * from the security filter chain to raw JDBC query spans, with nothing naming the handler or
 * grouping its work. See {@link AbstractMethodObservationAspect} for the shared span shape.
 *
 * <p>{@code @RestController} is meta-annotated with {@code @Controller}, but AspectJ {@code @within}
 * matches only directly-present annotations, so both stereotypes are listed explicitly. Methods or
 * classes already carrying {@code @Observed} are skipped to avoid double spans. Gated by
 * {@code dev.tylercash.observability.instrument-controllers} (default on).
 */
@Aspect
public class ControllerMethodObservationAspect extends AbstractMethodObservationAspect {

    static final String OBSERVATION_NAME = "controller.method";

    public ControllerMethodObservationAspect(ObservationRegistry registry) {
        super(registry, OBSERVATION_NAME);
    }

    @Around("(@within(org.springframework.web.bind.annotation.RestController)"
            + " || @within(org.springframework.stereotype.Controller))"
            + " && execution(public * *(..))"
            + " && !@annotation(io.micrometer.observation.annotation.Observed)"
            + " && !@within(io.micrometer.observation.annotation.Observed)")
    public Object aroundController(ProceedingJoinPoint joinPoint) throws Throwable {
        return observe(joinPoint);
    }
}
