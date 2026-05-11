package dev.tylercash.event.admin;

import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tylercash.event.lifecycle.ListenerInvocation;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.lifecycle.ListenerInvocationStatus;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Integration tests for {@link AdminMonitorController} — the read-only dashboard endpoints.
 *
 * <p><b>Parallel-safety:</b> {@code /admin/activity} performs a global {@code
 * findTop50ByOrderByUpdatedAtDesc} scan and an unconditional {@code countByStatus} / {@code
 * countStuck}. Without isolation, sibling test classes that seed listener_invocation rows on the
 * same DB would leak into our assertions. We pin to an isolated database. Tests own their data
 * via {@link TestIds}; we assert on payload shape and on entries scoped to our per-test guildId,
 * not on absolute global counts.
 */
class AdminMonitorControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String BOT_ADMIN = TestIds.nextSnowflake();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, AdminMonitorControllerHttpIntegrationTest.class);
        r.add("dev.tylercash.bot-admins[0]", () -> BOT_ADMIN);
    }

    @Autowired
    private ListenerInvocationRepository invocations;

    private String regularMember;
    private String creator;
    private long guildId;

    @BeforeEach
    void allocateTestIds() {
        regularMember = TestIds.nextSnowflake();
        creator = TestIds.nextSnowflake();
        guildId = TestIds.nextLong();

        // The /admin/health endpoint reads JDA.getStatus() / getGuilds() to compute the discord
        // component. The parent harness mocks the JDA bean as a bare Mockito mock so default
        // returns are null / 0; lenient-stub a sensible CONNECTED status so the response
        // serialises cleanly.
        lenient().when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        lenient().when(jda.getGuilds()).thenReturn(Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Auth gates (all three endpoints share requireBotAdmin)
    // -------------------------------------------------------------------------

    @Test
    void anonymous_health_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymous_jobs_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymous_activity_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/activity")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonBotAdmin_health_returns403() throws Exception {
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/health").with(authedAs(regularMember)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /admin/health
    // -------------------------------------------------------------------------

    @Test
    void botAdmin_health_returnsAllComponentsAndUptime() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/health").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.bot.status").value("ok"))
                .andExpect(jsonPath("$.components.discord").exists())
                .andExpect(jsonPath("$.components.database.status").value("ok"))
                .andExpect(jsonPath("$.components.scheduler").exists())
                .andExpect(jsonPath("$.components.listenerOutbox").exists())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.syncedAt").isString());
    }

    // -------------------------------------------------------------------------
    // GET /admin/jobs
    // -------------------------------------------------------------------------

    @Test
    void botAdmin_jobs_returnsArray() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/jobs").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // GET /admin/activity — guild-scoped vs global
    // -------------------------------------------------------------------------

    @Test
    void botAdmin_activity_scopedByGuild_returnsOnlyOurRowsForOurEvent() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");
        fixtures.registerMember(creator, guildId, "Creator", "creator");

        UUID eventId = fixtures.seedEvent(guildId, creator, "Activity Test " + TestIds.nextLong());
        String type = "TestType_" + TestIds.nextLong();
        String listener = "TestListener_" + TestIds.nextLong();
        seedInvocation(eventId, type, listener, ListenerInvocationStatus.FAILED, 2, "boom");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/activity")
                        .queryParam("guildId", String.valueOf(guildId))
                        .with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // findRecentForGuild only returns rows whose event lives in this guild, so any
                // hits MUST belong to us. We assert on the first row.
                .andExpect(jsonPath("$[0].guildId").value(String.valueOf(guildId)))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].lifecycleEventType").value(type))
                .andExpect(jsonPath("$[0].listenerName").value(listener))
                .andExpect(jsonPath("$[0].kind").value("fail"))
                .andExpect(jsonPath("$[0].attempts").value(2));
    }

    @Test
    void botAdmin_activity_invalidGuildId_returns400() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/activity")
                        .queryParam("guildId", "not-a-number")
                        .with(authedAs(BOT_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void botAdmin_activity_global_returnsArrayShape() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/activity").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedInvocation(
            UUID eventId, String type, String listener, ListenerInvocationStatus status, int attempts, String error) {
        ListenerInvocation row = new ListenerInvocation();
        row.setEventId(eventId);
        row.setLifecycleEventType(type);
        row.setListenerName(listener);
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setLastAttemptAt(Instant.now());
        row.setNextRetryAt(Instant.now());
        row.setLastError(error);
        invocations.save(row);
    }
}
