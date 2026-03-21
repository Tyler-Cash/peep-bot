package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventUpdateDto;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventControllerSecurityTest {

    @Mock
    private EventService eventService;

    @Mock
    private DiscordService discordService;

    @Mock
    private DiscordConfiguration discordConfiguration;

    @Mock
    private AttendanceService attendanceService;

    @Mock
    private DiscordUserCacheService discordUserCacheService;

    @Mock
    private OAuth2User principal;

    private EventController controller;

    private static final long GUILD_ID = 123456789L;
    private static final String ATTACKER_ID = "111222333";
    private static final String CREATOR_ID = "444555666";

    @BeforeEach
    void setUp() {
        controller = new EventController(
                eventService, discordService, discordConfiguration, attendanceService, discordUserCacheService);
        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(ATTACKER_ID);
    }

    @Test
    void updateEvent_nonCreatorNonAdmin_shouldThrowForbidden() {
        UUID eventId = UUID.randomUUID();
        EventUpdateDto updateDto = new EventUpdateDto();
        updateDto.setId(eventId);
        updateDto.setName("Hacked Name");
        updateDto.setAccepted(Collections.emptySet());

        Event existingEvent = new Event();
        existingEvent.setId(eventId);
        existingEvent.setCreator(CREATOR_ID);
        existingEvent.setName("Original Name");

        when(eventService.getEvent(eventId)).thenReturn(existingEvent);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(ATTACKER_ID)))
                .thenReturn(false);

        // This should fail currently because there's no check in the controller yet
        assertThatThrownBy(() -> controller.updateEvent(updateDto, principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void updateEvent_creator_shouldAllow() {
        UUID eventId = UUID.randomUUID();
        EventUpdateDto updateDto = new EventUpdateDto();
        updateDto.setId(eventId);
        updateDto.setName("New Name");
        updateDto.setAccepted(Collections.emptySet());

        Event existingEvent = new Event();
        existingEvent.setId(eventId);
        existingEvent.setCreator(ATTACKER_ID); // User is the creator
        existingEvent.setName("Original Name");

        when(eventService.getEvent(eventId)).thenReturn(existingEvent);

        controller.updateEvent(updateDto, principal);

        verify(eventService).updateEvent(any(Event.class));
    }

    @Test
    void updateEvent_admin_shouldAllow() {
        UUID eventId = UUID.randomUUID();
        EventUpdateDto updateDto = new EventUpdateDto();
        updateDto.setId(eventId);
        updateDto.setName("Admin Edit");
        updateDto.setAccepted(Collections.emptySet());

        Event existingEvent = new Event();
        existingEvent.setId(eventId);
        existingEvent.setCreator(CREATOR_ID);
        existingEvent.setName("Original Name");

        when(eventService.getEvent(eventId)).thenReturn(existingEvent);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(ATTACKER_ID)))
                .thenReturn(true);

        controller.updateEvent(updateDto, principal);

        verify(eventService).updateEvent(any(Event.class));
    }
}
