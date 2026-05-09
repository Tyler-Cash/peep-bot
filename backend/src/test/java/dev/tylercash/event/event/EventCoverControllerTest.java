package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.Event;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

class EventCoverControllerTest {

    private static final String SNOWFLAKE = "111";
    private static final long GUILD_ID = 999L;

    private static OAuth2User principal(String id) {
        return new DefaultOAuth2User(java.util.List.of(), Map.of("id", id), "id");
    }

    @Test
    void returnsImageBytesWhenCoverPresent() {
        EventRepository repo = mock(EventRepository.class);
        GuildMembershipService membership = mock(GuildMembershipService.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setServerId(GUILD_ID);
        event.setCoverImageBytes(new byte[] {1, 2, 3});
        event.setCoverImageContentType("image/jpeg");
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo, membership);
        ResponseEntity<byte[]> response = controller.getCover(id, principal(SNOWFLAKE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=86400, public");
    }

    @Test
    void returns404WhenNoCover() {
        EventRepository repo = mock(EventRepository.class);
        GuildMembershipService membership = mock(GuildMembershipService.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setServerId(GUILD_ID);
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo, membership);
        assertThat(controller.getCover(id, principal(SNOWFLAKE)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns404WhenEventMissing() {
        EventRepository repo = mock(EventRepository.class);
        GuildMembershipService membership = mock(GuildMembershipService.class);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        EventCoverController controller = new EventCoverController(repo, membership);
        assertThatThrownBy(() -> controller.getCover(id, principal(SNOWFLAKE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void defaultsToJpegContentTypeWhenMissing() {
        EventRepository repo = mock(EventRepository.class);
        GuildMembershipService membership = mock(GuildMembershipService.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setServerId(GUILD_ID);
        event.setCoverImageBytes(new byte[] {9});
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo, membership);
        assertThat(controller
                        .getCover(id, principal(SNOWFLAKE))
                        .getHeaders()
                        .getContentType()
                        .toString())
                .isEqualTo("image/jpeg");
    }

    @Test
    void rejectsAnonymousWith401() {
        EventRepository repo = mock(EventRepository.class);
        GuildMembershipService membership = mock(GuildMembershipService.class);
        UUID id = UUID.randomUUID();

        EventCoverController controller = new EventCoverController(repo, membership);
        assertThatThrownBy(() -> controller.getCover(id, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }
}
