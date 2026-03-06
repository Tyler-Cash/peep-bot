package dev.tylercash.event.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LiquibaseMigrationTest {

    // 16 changesets run before the data migration (through "create attendance table")
    // Changesets 17-20: migrate creator, seed cache, migrate attendees, drop old columns
    private static final int CHANGESETS_BEFORE_DATA_MIGRATION = 16;

    @Container
    private static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    private Connection createIsolatedConnection() throws Exception {
        String dbName = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }
        String url = pg.getJdbcUrl().replaceFirst("/[^/]*$", "/" + dbName);
        return DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
    }

    private Liquibase createLiquibase(Connection connection) throws Exception {
        Database database =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase("db/changelog/db.changelog-master.yaml", new ClasspathPrefixResourceAccessor(), database);
    }

    @Test
    @DisplayName("Migration changesets correctly transform JSON attendees to attendance table and seed cache")
    void migrationTransformsExistingData() throws Exception {
        try (Connection conn = createIsolatedConnection()) {

            Liquibase liquibase = createLiquibase(conn);

            // Run schema changesets only (stop before data migration)
            liquibase.update(CHANGESETS_BEFORE_DATA_MIGRATION, "");

            // Insert test data in the old JSON format
            UUID eventId = UUID.randomUUID();
            try (Statement stmt = conn.createStatement()) {
                String acceptedJson =
                        """
                        [{"snowflake":"111","name":"Alice","instant":"2025-06-01T10:00:00Z"},\
                        {"snowflake":"222","name":"Bob","instant":"2025-06-01T11:00:00Z"},\
                        {"snowflake":"","name":"GuestNoSnowflake","instant":"2025-06-01T12:00:00Z"}]""";
                String declinedJson =
                        """
                        [{"snowflake":"333","name":"Charlie","instant":"2025-06-02T10:00:00Z"}]""";
                String maybeJson =
                        """
                        [{"snowflake":"444","name":"Diana","instant":"2025-06-03T10:00:00Z"}]""";

                stmt.executeUpdate(
                        """
                        INSERT INTO event (id, message_id, server_id, channel_id, state, name, description,
                                           capacity, cost, date_time, notifications, creator, accepted, declined, maybe)
                        VALUES ('%s', 999, 888, 777, 'PLANNED', 'Test Event', 'A test',
                                10, 0, '2025-06-15T18:00:00+10:00', '[]', 'Alice', '%s', '%s', '%s')
                        """
                                .formatted(eventId, acceptedJson, declinedJson, maybeJson));
            }

            // Run the data migration changesets (17-20)
            liquibase.update();

            // Verify: creator migrated from "Alice" to snowflake "111"
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT creator FROM event WHERE id = '%s'".formatted(eventId));
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("creator"))
                        .as("Creator should be migrated from username 'Alice' to snowflake '111'")
                        .isEqualTo("111");
            }

            // Verify: discord_user_cache seeded with snowflake users (not empty-snowflake guests)
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery("SELECT snowflake, display_name FROM discord_user_cache ORDER BY snowflake");
                List<String> cachedSnowflakes = new ArrayList<>();
                List<String> cachedNames = new ArrayList<>();
                while (rs.next()) {
                    cachedSnowflakes.add(rs.getString("snowflake"));
                    cachedNames.add(rs.getString("display_name"));
                }
                assertThat(cachedSnowflakes)
                        .as("Cache should contain all non-empty snowflakes from JSON data")
                        .containsExactlyInAnyOrder("111", "222", "333", "444");
                assertThat(cachedNames).containsExactlyInAnyOrder("Alice", "Bob", "Charlie", "Diana");
            }

            // Verify: attendance table populated correctly
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        """
                        SELECT snowflake, name, status, owner_snowflake, recorded_at
                        FROM attendance WHERE event_id = '%s' ORDER BY recorded_at
                        """
                                .formatted(eventId));

                List<AttendanceRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new AttendanceRow(
                            rs.getString("snowflake"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getString("owner_snowflake"),
                            rs.getString("recorded_at")));
                }

                assertThat(rows).hasSize(5);

                // Discord users: snowflake set, name null
                AttendanceRow alice = findBySnowflake(rows, "111");
                assertThat(alice.name).as("Discord user should have null name").isNull();
                assertThat(alice.status).isEqualTo("ACCEPTED");
                assertThat(alice.ownerSnowflake)
                        .as("Discord user should have null owner_snowflake")
                        .isNull();
                assertThat(alice.recordedAt)
                        .as("Timestamp should be preserved from JSON instant")
                        .isNotNull();

                assertThat(findBySnowflake(rows, "222").status).isEqualTo("ACCEPTED");
                assertThat(findBySnowflake(rows, "333").status).isEqualTo("DECLINED");
                assertThat(findBySnowflake(rows, "444").status).isEqualTo("MAYBE");

                // +1 guest: snowflake null, name set, owner_snowflake = creator (now "111" after migration)
                AttendanceRow guest = findByName(rows, "GuestNoSnowflake");
                assertThat(guest.snowflake)
                        .as("+1 guest should have null snowflake")
                        .isNull();
                assertThat(guest.status).isEqualTo("ACCEPTED");
                assertThat(guest.ownerSnowflake)
                        .as("+1 guest owner_snowflake should be the migrated creator snowflake")
                        .isEqualTo("111");
            }

            // Verify: old JSON columns dropped
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'event' AND column_name IN ('accepted', 'declined', 'maybe')
                        """);
                assertThat(rs.next())
                        .as("Old JSON columns should be dropped after migration")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Migration handles events with null/empty JSON columns gracefully")
    void migrationHandlesNullAndEmptyJson() throws Exception {
        try (Connection conn = createIsolatedConnection()) {

            Liquibase liquibase = createLiquibase(conn);
            liquibase.update(CHANGESETS_BEFORE_DATA_MIGRATION, "");

            UUID eventWithNulls = UUID.randomUUID();
            UUID eventWithEmpty = UUID.randomUUID();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        """
                        INSERT INTO event (id, message_id, server_id, channel_id, state, name, description,
                                           capacity, cost, date_time, notifications, creator,
                                           accepted, declined, maybe)
                        VALUES ('%s', 9990, 888, 7770, 'PLANNED', 'Null Event', 'desc',
                                0, 0, '2025-07-01T18:00:00+10:00', '[]', 'SomeUser',
                                NULL, NULL, NULL)
                        """
                                .formatted(eventWithNulls));

                stmt.executeUpdate(
                        """
                        INSERT INTO event (id, message_id, server_id, channel_id, state, name, description,
                                           capacity, cost, date_time, notifications, creator,
                                           accepted, declined, maybe)
                        VALUES ('%s', 9991, 888, 7771, 'PLANNED', 'Empty Event', 'desc',
                                0, 0, '2025-07-02T18:00:00+10:00', '[]', 'AnotherUser',
                                '', '', '')
                        """
                                .formatted(eventWithEmpty));
            }

            // Should not throw
            liquibase.update();

            // Verify no attendance records created for events with no attendees
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM attendance WHERE event_id IN ('%s', '%s')"
                        .formatted(eventWithNulls, eventWithEmpty));
                rs.next();
                assertThat(rs.getInt(1))
                        .as("No attendance records should be created for events with null/empty JSON")
                        .isZero();
            }

            // Verify creator unchanged (no matching snowflake to migrate to)
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT creator FROM event WHERE id = '%s'".formatted(eventWithNulls));
                rs.next();
                assertThat(rs.getString("creator"))
                        .as("Creator should remain unchanged when no match found")
                        .isEqualTo("SomeUser");
            }
        }
    }

    private static AttendanceRow findBySnowflake(List<AttendanceRow> rows, String snowflake) {
        return rows.stream()
                .filter(r -> snowflake.equals(r.snowflake))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row with snowflake: " + snowflake));
    }

    private static AttendanceRow findByName(List<AttendanceRow> rows, String name) {
        return rows.stream()
                .filter(r -> name.equals(r.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row with name: " + name));
    }

    private record AttendanceRow(
            String snowflake, String name, String status, String ownerSnowflake, String recordedAt) {}

    /**
     * ResourceAccessor that strips the "classpath:" prefix so Liquibase can resolve
     * Spring-style classpath references (e.g., the Spring Session SQL schema file).
     */
    private static class ClasspathPrefixResourceAccessor extends ClassLoaderResourceAccessor {
        private static final String CLASSPATH_PREFIX = "classpath:";

        @Override
        public List<Resource> search(String path, boolean recursive) throws IOException {
            return super.search(stripPrefix(path), recursive);
        }

        @Override
        public List<Resource> getAll(String path) throws IOException {
            return super.getAll(stripPrefix(path));
        }

        private static String stripPrefix(String path) {
            if (path != null && path.startsWith(CLASSPATH_PREFIX)) {
                return path.substring(CLASSPATH_PREFIX.length());
            }
            return path;
        }
    }
}
