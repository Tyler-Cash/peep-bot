package dev.tylercash.event.lifecycle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tylercash.event.lifecycle.ListenerInvocation;
import dev.tylercash.event.lifecycle.ListenerInvocationId;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.lifecycle.ListenerInvocationStatus;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Integration tests for {@link ListenerInvocationAdminController} — the operator endpoints for
 * the lifecycle outbox.
 *
 * <p><b>Parallel-safety:</b> tests own their rows. Every method seeds a {@link ListenerInvocation}
 * keyed by a per-test {@code eventId} (UUID), {@code type}, and {@code listenerName}, then
 * asserts only on that key. No global truncates. Pinned to an isolated database so sibling
 * lifecycle/saga tests that scan global listener_invocation state can't drift this row in or out
 * mid-test.
 */
class ListenerInvocationAdminControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String BOT_ADMIN = TestIds.nextSnowflake();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, ListenerInvocationAdminControllerHttpIntegrationTest.class);
        r.add("dev.tylercash.bot-admins[0]", () -> BOT_ADMIN);
    }

    @Autowired
    private ListenerInvocationRepository repo;

    private String regularMember;
    private String creator;
    private long guildId;
    private UUID eventId;
    private String type;
    private String listenerName;

    @BeforeEach
    void allocateTestIds() {
        regularMember = TestIds.nextSnowflake();
        creator = TestIds.nextSnowflake();
        guildId = TestIds.nextLong();
        type = "TestType_" + TestIds.nextLong();
        listenerName = "TestListener_" + TestIds.nextLong();
    }

    // -------------------------------------------------------------------------
    // Auth gates
    // -------------------------------------------------------------------------

    @Test
    void anonymous_retryNow_returns401() throws Exception {
        UUID someEventId = UUID.randomUUID();
        mockMvc.perform(MockMvcRequestBuilders.post(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}/retry-now",
                                someEventId,
                                type,
                                listenerName)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonBotAdmin_retryNow_returns403() throws Exception {
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");
        UUID someEventId = UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.post(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}/retry-now",
                                someEventId,
                                type,
                                listenerName)
                        .with(authedAs(regularMember))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonBotAdmin_abandon_returns403() throws Exception {
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");
        UUID someEventId = UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.delete(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}",
                                someEventId,
                                type,
                                listenerName)
                        .with(authedAs(regularMember))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // retry-now
    // -------------------------------------------------------------------------

    @Test
    void retryNow_existingFailedRow_flipsToPendingAndArmsRetry() throws Exception {
        seedEventAndAllocateId();
        Instant farFuture = Instant.now().plusSeconds(86_400);
        seed(ListenerInvocationStatus.FAILED, 3, "kaboom", farFuture);

        mockMvc.perform(MockMvcRequestBuilders.post(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}/retry-now",
                                eventId,
                                type,
                                listenerName)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        ListenerInvocation refreshed = repo.findById(new ListenerInvocationId(eventId, type, listenerName))
                .orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ListenerInvocationStatus.PENDING);
        // Retry was armed to (approximately) now — strictly earlier than the far-future seeded value.
        assertThat(refreshed.getNextRetryAt()).isBefore(farFuture);
        // Attempts counter is not reset — operator wants to see history.
        assertThat(refreshed.getAttempts()).isEqualTo(3);
    }

    @Test
    void retryNow_missingRow_returns404() throws Exception {
        UUID someEventId = UUID.randomUUID();
        mockMvc.perform(MockMvcRequestBuilders.post(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}/retry-now",
                                someEventId,
                                type,
                                listenerName)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // abandon
    // -------------------------------------------------------------------------

    @Test
    void abandon_existingRow_deletesAndReturns204() throws Exception {
        seedEventAndAllocateId();
        seed(ListenerInvocationStatus.FAILED, 5, "permanent failure", Instant.now());
        ListenerInvocationId id = new ListenerInvocationId(eventId, type, listenerName);
        assertThat(repo.findById(id)).isPresent();

        mockMvc.perform(MockMvcRequestBuilders.delete(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}", eventId, type, listenerName)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    void abandon_missingRow_returns404() throws Exception {
        UUID someEventId = UUID.randomUUID();
        mockMvc.perform(MockMvcRequestBuilders.delete(
                                "/admin/listener-invocations/{eventId}/{type}/{listener}",
                                someEventId,
                                type,
                                listenerName)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Seed a PLANNED event row first — {@code listener_invocation.event_id} has a FK to {@code
     * event(id)}. Sets {@link #eventId} to the new event's UUID for the subsequent {@link #seed}
     * call.
     */
    private void seedEventAndAllocateId() {
        fixtures.registerMember(creator, guildId, "Creator", "creator");
        eventId = fixtures.seedEvent(guildId, creator, "FK seed " + TestIds.nextLong());
    }

    private void seed(ListenerInvocationStatus status, int attempts, String lastError, Instant nextRetryAt) {
        ListenerInvocation row = new ListenerInvocation();
        row.setEventId(eventId);
        row.setLifecycleEventType(type);
        row.setListenerName(listenerName);
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setLastAttemptAt(Instant.now());
        row.setNextRetryAt(nextRetryAt);
        row.setLastError(lastError);
        repo.save(row);
    }
}
