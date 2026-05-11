package dev.tylercash.event.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tylercash.event.global.EventCreationToggle;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * End-to-end integration test for the bot-admin event-creation kill-switch.
 *
 * <p>Exercises three things together that other tests cover only in isolation:
 * <ol>
 *   <li>the {@code /admin/event-creation/...} endpoints (auth gates, state, audit),</li>
 *   <li>the in-memory {@link EventCreationToggle} flag flip, and</li>
 *   <li>that {@code PUT /event} actually honours the toggle (503 when disabled).</li>
 * </ol>
 *
 * <p><b>Parallel-safety:</b> {@link EventCreationToggle} is a singleton bean — flipping it would
 * pollute sibling test classes that share the same Spring context. We pin this class to its own
 * cached context by registering an isolated database via
 * {@link SharedPostgres#registerIsolatedDatabase}, and we restore the toggle to {@code enabled}
 * in {@link #restoreToggle()} after every test. Test methods within a single class run
 * sequentially in JUnit's default mode, so within-class flips are safe.
 */
class AdminEventCreationToggleHttpIntegrationTest extends AbstractHttpIntegrationTest {

    // Static so @DynamicPropertySource can wire it into bot-admins[0] before the context starts.
    private static final String BOT_ADMIN = TestIds.nextSnowflake();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, AdminEventCreationToggleHttpIntegrationTest.class);
        r.add("dev.tylercash.bot-admins[0]", () -> BOT_ADMIN);
    }

    @Autowired
    private EventCreationToggle eventCreationToggle;

    private String regularMember;
    private long guildId;

    @BeforeEach
    void allocateTestIds() {
        regularMember = TestIds.nextSnowflake();
        guildId = TestIds.nextLong();
    }

    @AfterEach
    void restoreToggle() {
        // Always leave the toggle enabled so a flaky test can't poison the cached context for
        // subsequent test classes in the same JVM.
        eventCreationToggle.enable();
    }

    // -------------------------------------------------------------------------
    // GET /admin/event-creation — auth gates + state
    // -------------------------------------------------------------------------

    @Test
    void anonymous_getState_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/event-creation")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonBotAdmin_getState_returns403() throws Exception {
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/event-creation").with(authedAs(regularMember)))
                .andExpect(status().isForbidden());
    }

    @Test
    void botAdmin_getState_returnsEnabledTrueByDefault() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/event-creation").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // -------------------------------------------------------------------------
    // POST /admin/event-creation/disable + /enable — auth gates + state transition
    // -------------------------------------------------------------------------

    @Test
    void anonymous_disable_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/disable")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonBotAdmin_disable_returns403() throws Exception {
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/disable")
                        .with(authedAs(regularMember))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void botAdmin_disableThenEnable_flipsStateBothWays() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/disable")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/event-creation").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/enable")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/event-creation").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // -------------------------------------------------------------------------
    // Cross-feature: toggle actually gates PUT /event
    // -------------------------------------------------------------------------

    @Test
    void putEvent_returns503_whenToggleDisabled() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");

        // Bot-admin disables event creation.
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/disable")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // A regular member's PUT /event now bounces with 503 — the toggle short-circuits before
        // any DB / Discord work. We don't bother stubbing DiscordService because it shouldn't
        // be reached.
        mockMvc.perform(MockMvcRequestBuilders.put("/event")
                        .with(authedAs(regularMember))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void putEvent_succeeds_afterReenable() throws Exception {
        fixtures.registerMember(BOT_ADMIN, guildId, "BotAdmin", "botadmin");
        fixtures.registerMember(regularMember, guildId, "Regular", "regular");
        stubDiscordForCreate();

        // Disable then re-enable through the admin endpoints.
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/disable")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/event-creation/enable")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // PUT /event now goes through cleanly.
        mockMvc.perform(MockMvcRequestBuilders.put("/event")
                        .with(authedAs(regularMember))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String eventBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "guildId",
                String.valueOf(guildId),
                "name",
                "Toggle Test Event " + TestIds.nextLong(),
                "description",
                "Test event for toggle integration",
                "dateTime",
                ZonedDateTime.now().plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "capacity",
                10));
    }

    private void stubDiscordForCreate() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(guildId);

        TextChannel channel = mock(TextChannel.class);
        when(channel.getIdLong()).thenReturn(TestIds.nextLong());
        when(channel.getGuild()).thenReturn(guild);
        when(discordService.createEventChannel(any())).thenReturn(channel);
        when(discordService.getChannel(any())).thenReturn(channel);

        Message message = mock(Message.class);
        when(message.getIdLong()).thenReturn(TestIds.nextLong());
        when(message.getGuildIdLong()).thenReturn(guildId);
        when(discordService.postEventMessage(any(), any())).thenReturn(message);

        Member member = mock(Member.class);
        User user = mock(User.class);
        when(user.getName()).thenReturn("regular");
        when(member.getUser()).thenReturn(user);
        when(member.getNickname()).thenReturn("Regular");
        when(member.getEffectiveName()).thenReturn("Regular");
        when(discordService.getMemberFromServer(eq(guildId), eq(Long.parseLong(regularMember))))
                .thenReturn(member);
    }
}
