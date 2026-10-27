package dev.tylercash.event.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AdminControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    // Static so @DynamicPropertySource can wire it into bot-admins[0] before the context starts.
    private static final String BOT_ADMIN = TestIds.nextSnowflake();

    @DynamicPropertySource
    static void registerBotAdmin(DynamicPropertyRegistry r) {
        r.add("dev.tylercash.bot-admins[0]", () -> BOT_ADMIN);
    }

    private String REGULAR_MEMBER;
    private long GUILD_A;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        REGULAR_MEMBER = TestIds.nextSnowflake();
        GUILD_A = TestIds.nextLong();
    }

    private void seedGuild(long guildId) {
        jdbc.execute("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled,"
                + " rewind_enabled)"
                + " VALUES ("
                + guildId
                + ", 'events', 'event-organiser', '✅', '❌', '❓', NOW(), true, false, false, false)"
                + " ON CONFLICT (guild_id) DO NOTHING");
    }

    @Test
    void anonymous_listGuilds_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/guilds")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonBotAdmin_listGuilds_returns403() throws Exception {
        fixtures.registerMember(REGULAR_MEMBER, GUILD_A, "Regular", "regular");

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/guilds").with(authedAs(REGULAR_MEMBER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void botAdmin_listGuilds_returnsGuildList() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/guilds").with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].guildId").value(String.valueOf(GUILD_A)))
                .andExpect(jsonPath("$[0].immichEnabled").value(false))
                .andExpect(jsonPath("$[0].rewindEnabled").value(false));
    }

    @Test
    void botAdmin_updateFeatures_flipsFlag() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.patch("/admin/guilds/{guildId}/features", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"immichEnabled\":true}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.immichEnabled").value(true))
                .andExpect(jsonPath("$.rewindEnabled").value(false));
    }

    @Test
    void botAdmin_updateFeatures_logsAudit() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);

        Logger adminLogger = (Logger) LoggerFactory.getLogger(AdminController.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        adminLogger.addAppender(listAppender);

        try {
            mockMvc.perform(MockMvcRequestBuilders.patch("/admin/guilds/{guildId}/features", GUILD_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"immichEnabled\":true}")
                            .with(authedAs(BOT_ADMIN))
                            .with(csrf()))
                    .andExpect(status().isOk());
        } finally {
            adminLogger.detachAppender(listAppender);
        }

        assertThat(listAppender.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("AUDIT bot-admin " + BOT_ADMIN)
                    .contains(String.valueOf(GUILD_A))
                    .contains("IMMICH")
                    .contains("false -> true");
        });
    }

    @Test
    void botAdmin_updateFeatures_unknownGuild_returns404() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        long unknownGuild = 99999L;

        mockMvc.perform(MockMvcRequestBuilders.patch("/admin/guilds/{guildId}/features", unknownGuild)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"immichEnabled\":true}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void botAdmin_updateFeatures_flipsTfnswFlag() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.patch("/admin/guilds/{guildId}/features", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tfnswEnabled\":true}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tfnswEnabled").value(true))
                .andExpect(jsonPath("$.immichEnabled").value(false));
    }

    @Test
    void botAdmin_updateFeatures_flipsTarotFlag() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.patch("/admin/guilds/{guildId}/features", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tarotEnabled\":true}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tarotEnabled").value(true))
                .andExpect(jsonPath("$.contractsEnabled").value(false));
    }

    @Test
    void botAdmin_tarotChannels_roundTrip() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);
        String channel = TestIds.nextSnowflake();

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/guilds/{guildId}/tarot", GUILD_A)
                        .with(authedAs(BOT_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedChannelIds").isEmpty())
                .andExpect(jsonPath("$.drawsLast7Days").value(0));

        mockMvc.perform(MockMvcRequestBuilders.put("/admin/guilds/{guildId}/tarot/channels", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelIds\":[\"" + channel + "\"]}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedChannelIds[0]").value(channel));

        mockMvc.perform(MockMvcRequestBuilders.put("/admin/guilds/{guildId}/tarot/channels", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelIds\":[]}")
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedChannelIds").isEmpty());
    }

    @Test
    void nonBotAdmin_tarotChannels_returns403() throws Exception {
        fixtures.registerMember(REGULAR_MEMBER, GUILD_A, "Regular", "regular");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.put("/admin/guilds/{guildId}/tarot/channels", GUILD_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelIds\":[]}")
                        .with(authedAs(REGULAR_MEMBER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
