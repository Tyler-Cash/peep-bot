package dev.tylercash.event.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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

    /**
     * Register Spring datasource pointing at a per-test-class database within the shared
     * container. Use this for tests that operate on DB-global state (schedulers, retry pollers,
     * the saga) — anything that scans rows across tenants and so cannot tolerate sibling-class
     * data. Liquibase populates the schema when the Spring context starts.
     */
    public static void registerIsolatedDatabase(DynamicPropertyRegistry r, Class<?> testClass) {
        String dbName = ("test_" + testClass.getSimpleName()).toLowerCase();
        ensureDatabase(dbName);
        r.add("spring.datasource.url", () -> CONTAINER.getJdbcUrl().replaceFirst("/[^/]*$", "/" + dbName));
        r.add("spring.datasource.username", CONTAINER::getUsername);
        r.add("spring.datasource.password", CONTAINER::getPassword);
    }

    private static synchronized void ensureDatabase(String dbName) {
        try (Connection c = DriverManager.getConnection(
                        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("already exists")) {
                throw new RuntimeException("Failed to create test database " + dbName, e);
            }
        }
    }
}
