package dev.tylercash.event.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres testcontainer per JVM, shared across every integration test.
 *
 * <p>Tests are expected to own the data they touch (unique IDs / names per test) and assert on
 * that data specifically — never "find all" or "row count == N". This lets the suite run in
 * parallel without per-class truncation, container restarts, or {@code @DirtiesContext}.
 *
 * <p>Not annotated {@code @Container} on purpose so Testcontainers never stops the parent
 * between classes. Ryuk's JVM-exit shutdown hook cleans it up.
 */
public final class SharedPostgres {

    public static final PostgreSQLContainer<?> CONTAINER;

    static {
        CONTAINER = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");
        CONTAINER.start();
    }

    private SharedPostgres() {}

    /** Register the shared container's JDBC URL/credentials with a Spring test context. */
    public static void registerProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        r.add("spring.datasource.username", CONTAINER::getUsername);
        r.add("spring.datasource.password", CONTAINER::getPassword);
    }
}
