package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class EventControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String USER = "500001";
    private static final String OTHER_USER = "500002";
    private static final long GUILD = 5001L;

    @Autowired
    private EventRepository eventRepository;

    // ---------------------------------------------------------------------------
    // Helper: set up Discord mocks needed by the INIT_CHANNEL state machine step.
    // ---------------------------------------------------------------------------
    private void mockDiscordEventChannelCreation() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);

        TextChannel textChannel = mock(TextChannel.class);
        when(textChannel.getIdLong()).thenReturn(9_000_001L);
        when(textChannel.getGuild()).thenReturn(guild);
        when(discordService.createEventChannel(any())).thenReturn(textChannel);
        when(discordService.getChannel(any())).thenReturn(textChannel);

        Message message = mock(Message.class);
        when(message.getIdLong()).thenReturn(9_000_002L);
        when(message.getGuildIdLong()).thenReturn(GUILD);
        when(discordService.postEventMessage(any(), any())).thenReturn(message);
    }

    private String futureDateTime() {
        return ZonedDateTime.now().plusDays(7).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ---------------------------------------------------------------------------
    // PUT /event — create
    // ---------------------------------------------------------------------------

    @Test
    void member_createsEvent_returnsIdAndPersists() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        mockDiscordEventChannelCreation();

        Member member = mock(Member.class);
        User user = mock(User.class);
        when(user.getName()).thenReturn("testuser");
        when(member.getUser()).thenReturn(user);
        when(member.getNickname()).thenReturn("Test User");
        when(member.getEffectiveName()).thenReturn("Test User");
        when(discordService.getMemberFromServer(eq(GUILD), eq(Long.parseLong(USER))))
                .thenReturn(member);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "guildId",
                String.valueOf(GUILD),
                "name",
                "Integration Test Event",
                "description",
                "A test event",
                "dateTime",
                futureDateTime(),
                "capacity",
                10));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.put("/event")
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Created event for Integration Test Event"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String idStr = objectMapper.readTree(responseBody).get("id").asText();
        UUID eventId = UUID.fromString(idStr);

        assertThat(eventRepository.findById(eventId)).isPresent();
    }

    @Test
    void member_createsEvent_invalidBody_returns400() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");

        // Missing required fields: name too short, no dateTime, no guildId
        String body = "{\"name\":\"\",\"capacity\":5}";

        mockMvc.perform(MockMvcRequestBuilders.put("/event")
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------
    // PATCH /event — update
    // ---------------------------------------------------------------------------

    @Test
    void member_updatesEvent_appliesNewFields() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Original Name");

        String newDateTime = ZonedDateTime.now().plusDays(14).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "id", eventId.toString(), "name", "Updated Name", "capacity", 20, "dateTime", newDateTime));

        mockMvc.perform(MockMvcRequestBuilders.patch("/event")
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Updated event for Updated Name"));

        // Verify updated fields via GET
        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.capacity").value(20));
    }

    // ---------------------------------------------------------------------------
    // GET /event — list
    // ---------------------------------------------------------------------------

    @Test
    void member_listsEvents_returnsPage() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        fixtures.seedEvent(GUILD, USER, "Event Alpha");
        fixtures.seedEvent(GUILD, USER, "Event Beta");

        mockMvc.perform(MockMvcRequestBuilders.get("/event")
                        .param("guildId", String.valueOf(GUILD))
                        .with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    // ---------------------------------------------------------------------------
    // GET /event/{id} — detail
    // ---------------------------------------------------------------------------

    @Test
    void member_getsEvent_returnsDetail() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Detail Event");

        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.name").value("Detail Event"))
                .andExpect(jsonPath("$.accepted").isArray());
    }

    @Test
    void member_getsEvent_unknownId_returns404() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");

        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", UUID.randomUUID())
                        .with(authedAs(USER)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------
    // POST /event/{id}/rsvp
    // ---------------------------------------------------------------------------

    @Test
    void member_rsvpGoing_persists() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        UUID eventId = fixtures.seedEvent(GUILD, OTHER_USER, "RSVP Event");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"going\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").isArray());

        // Verify via GET that USER is in accepted list
        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.accepted[?(@.snowflake == '" + USER + "')]").exists());
    }

    @Test
    void member_rsvpMaybe_persists() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        UUID eventId = fixtures.seedEvent(GUILD, OTHER_USER, "RSVP Maybe Event");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"maybe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maybe").isArray());

        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maybe[?(@.snowflake == '" + USER + "')]").exists());
    }

    @Test
    void member_rsvpDeclined_persists() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        UUID eventId = fixtures.seedEvent(GUILD, OTHER_USER, "RSVP Declined Event");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"declined\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declined").isArray());

        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.declined[?(@.snowflake == '" + USER + "')]").exists());
    }

    @Test
    void member_rsvpMoreThan6hAfterStart_returns403() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");
        UUID eventId = fixtures.seedEvent(
                GUILD, OTHER_USER, "Past Event", ZonedDateTime.now().minusHours(7));

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"going\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_rsvpWithin6hAfterStart_succeeds() throws Exception {
        fixtures.registerMember(USER, GUILD, "Test User", "testuser");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");
        UUID eventId = fixtures.seedEvent(
                GUILD, OTHER_USER, "Just-Started Event", ZonedDateTime.now().minusMinutes(30));

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"going\"}"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------
    // POST /event/{id}/cancel — admin only
    // ---------------------------------------------------------------------------

    @Test
    void admin_cancelsEvent_marksCancelled() throws Exception {
        fixtures.registerMember(USER, GUILD, "Admin User", "adminuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Cancel Me Event");
        when(discordService.isUserOrganiserOfServer(eq(GUILD), eq(Long.parseLong(USER))))
                .thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/cancel", eventId)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Event cancelled"));

        // Cancel is now async via the event bus. Verify the outbox row was created for the
        // EventCancelListener, which will asynchronously transition the event to CANCELLED.
        assertThat(jdbc.queryForList(
                        "SELECT 1 FROM listener_invocation WHERE event_id = ? AND lifecycle_event_type = 'EventCancelRequested'",
                        eventId))
                .isNotEmpty();
    }

    // ---------------------------------------------------------------------------
    // POST /event/{id}/private-channel — admin only
    // ---------------------------------------------------------------------------

    @Test
    void admin_createsPrivateChannel_returnsOk() throws Exception {
        fixtures.registerMember(USER, GUILD, "Admin User", "adminuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Private Channel Event");
        when(discordService.isUserOrganiserOfServer(eq(GUILD), eq(Long.parseLong(USER))))
                .thenReturn(true);
        doNothing().when(discordService).createPrivateEventChannel(any());

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/private-channel", eventId)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Private channel created"));

        verify(discordService).createPrivateEventChannel(any());
    }

    // ---------------------------------------------------------------------------
    // DELETE /event/{id}/attendee
    // ---------------------------------------------------------------------------

    @Test
    void admin_removesAttendee_succeeds() throws Exception {
        fixtures.registerMember(USER, GUILD, "Admin User", "adminuser");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Remove Attendee Event");

        // RSVP OTHER_USER to the event
        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/rsvp", eventId)
                        .with(authedAs(OTHER_USER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"going\"}"))
                .andExpect(status().isOk());

        when(discordService.isUserOrganiserOfServer(eq(GUILD), eq(Long.parseLong(USER))))
                .thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.delete("/event/{id}/attendee", eventId)
                        .param("snowflake", OTHER_USER)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Removed attendee"));

        // Verify OTHER_USER no longer in accepted list
        mockMvc.perform(MockMvcRequestBuilders.get("/event/{id}", eventId).with(authedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted[?(@.snowflake == '" + OTHER_USER + "')]")
                        .doesNotExist());
    }

    @Test
    void nonAdmin_removesOtherUser_returns403() throws Exception {
        fixtures.registerMember(USER, GUILD, "Regular User", "regularuser");
        fixtures.registerMember(OTHER_USER, GUILD, "Other User", "otheruser");
        UUID eventId = fixtures.seedEvent(GUILD, OTHER_USER, "Forbidden Remove Event");

        // USER is not admin
        when(discordService.isUserOrganiserOfServer(anyLong(), eq(Long.parseLong(USER))))
                .thenReturn(false);

        mockMvc.perform(MockMvcRequestBuilders.delete("/event/{id}/attendee", eventId)
                        .param("snowflake", OTHER_USER)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeAttendee_neitherSnowflakeNorName_returns400() throws Exception {
        fixtures.registerMember(USER, GUILD, "Admin User", "adminuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Bad Remove Event");

        mockMvc.perform(MockMvcRequestBuilders.delete("/event/{id}/attendee", eventId)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------
    // POST /event/{id}/recategorize — admin only
    // ---------------------------------------------------------------------------

    @Test
    void admin_recategorizes_returnsOk() throws Exception {
        fixtures.registerMember(USER, GUILD, "Admin User", "adminuser");
        UUID eventId = fixtures.seedEvent(GUILD, USER, "Recategorize Event");
        when(discordService.isUserOrganiserOfServer(eq(GUILD), eq(Long.parseLong(USER))))
                .thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.post("/event/{id}/recategorize", eventId)
                        .with(authedAs(USER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Recategorization triggered"));
    }
}
