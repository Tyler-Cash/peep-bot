package dev.tylercash.event.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichAlbumResponse;
import dev.tylercash.event.immich.ImmichService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

class GalleryControllerTest {
    private static final long GUILD_ID = 12345L;
    private static final String SNOWFLAKE = "user-snowflake-1";
    private static final String USERNAME = "tylercash";
    private static final String ALBUM_ID = "album-abc";
    private static final Instant NOW = Instant.parse("2026-04-28T20:00:00Z");

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final ImmichService immichService = mock(ImmichService.class);
    private final GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
    private final GalleryController controller =
            new GalleryController(eventRepository, immichService, guildMembershipService, clock);

    private OAuth2User principal() {
        OAuth2User p = mock(OAuth2User.class);
        when(p.getAttribute("id")).thenReturn(SNOWFLAKE);
        when(p.getAttribute("username")).thenReturn(USERNAME);
        return p;
    }

    private Event event(String albumId, String shareKey, int acceptedCount) {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setName("trivia at the dog & duck");
        e.setDateTime(ZonedDateTime.now(clock));
        e.setImmichAlbumId(albumId);
        if (shareKey != null) e.setImmichShareKey(shareKey);
        Set<Attendee> accepted = new java.util.HashSet<>();
        for (int i = 0; i < acceptedCount; i++) {
            accepted.add(Attendee.createWithTimestamp("snow-" + i, "name-" + i, NOW));
        }
        e.setAccepted(accepted);
        return e;
    }

    // ============================================================================
    // getGallery — list endpoint permutations
    // ============================================================================

    @Test
    @DisplayName("list: album with photos is rendered with attendees + BFF urls")
    void list_albumWithPhotos_renders() {
        Event ev = event(ALBUM_ID, "share-key-old", 3);
        when(eventRepository.findGalleryEventsForUser(GUILD_ID, SNOWFLAKE)).thenReturn(List.of(ev));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "name", "thumb-asset", 8)));

        List<GalleryAlbumDto> result = controller.getGallery(GUILD_ID, principal());

        assertThat(result).hasSize(1);
        GalleryAlbumDto dto = result.get(0);
        assertThat(dto.assetCount()).isEqualTo(8);
        assertThat(dto.albumUrl()).isEqualTo("/api/gallery/" + ALBUM_ID + "/open");
        assertThat(dto.thumbnailUrl()).isEqualTo("/api/gallery/thumbnail/" + ALBUM_ID);
        assertThat(dto.attendees()).hasSize(3);
    }

    @Test
    @DisplayName("list: album with zero photos is filtered out")
    void list_albumZeroPhotos_filteredOut() {
        when(eventRepository.findGalleryEventsForUser(GUILD_ID, SNOWFLAKE))
                .thenReturn(List.of(event(ALBUM_ID, "share", 2)));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 0)));

        assertThat(controller.getGallery(GUILD_ID, principal())).isEmpty();
    }

    @Test
    @DisplayName("list: album that no longer exists in Immich is filtered out")
    void list_albumMissingInImmich_filteredOut() {
        when(eventRepository.findGalleryEventsForUser(GUILD_ID, SNOWFLAKE))
                .thenReturn(List.of(event(ALBUM_ID, "share", 5)));
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.empty());

        assertThat(controller.getGallery(GUILD_ID, principal())).isEmpty();
    }

    @Test
    @DisplayName("list: legacy event without persisted shareKey still gets BFF albumUrl")
    void list_legacyEventNoShareKey_stillRenders() {
        Event ev = event(ALBUM_ID, null, 1);
        when(eventRepository.findGalleryEventsForUser(GUILD_ID, SNOWFLAKE)).thenReturn(List.of(ev));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 4)));

        List<GalleryAlbumDto> result = controller.getGallery(GUILD_ID, principal());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).albumUrl()).isEqualTo("/api/gallery/" + ALBUM_ID + "/open");
    }

    @Test
    @DisplayName("list: asserts guild membership before doing anything")
    void list_assertsGuildMembership() {
        controller.getGallery(GUILD_ID, principal());
        verify(guildMembershipService).assertMember(SNOWFLAKE, GUILD_ID);
    }

    // ============================================================================
    // openAlbum — per-user share-link redirect endpoint
    // ============================================================================

    @Test
    @DisplayName("open: returns JSON share URL + expiry; fresh share is generated with username prefix")
    void open_happyPath() {
        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.of(event(ALBUM_ID, null, 1)));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 5)));
        when(immichService.createSharedLink(
                        org.mockito.ArgumentMatchers.eq(ALBUM_ID),
                        org.mockito.ArgumentMatchers.startsWith(USERNAME + "-"),
                        org.mockito.ArgumentMatchers.eq(NOW.plus(GalleryController.USER_SHARE_TTL))))
                .thenReturn(Optional.of("fresh-share-key"));
        when(immichService.getShareUrl("fresh-share-key"))
                .thenReturn("https://immich.example.com/share/fresh-share-key");

        Map<String, String> resp = controller.openAlbum(ALBUM_ID, principal());

        assertThat(resp.get("url")).isEqualTo("https://immich.example.com/share/fresh-share-key");
        assertThat(resp.get("expiresAt"))
                .isEqualTo(NOW.plus(GalleryController.USER_SHARE_TTL).toString());
    }

    @Test
    @DisplayName("open: user who didn't accept the event gets 404 (authz)")
    void open_unauthorizedUser() {
        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.openAlbum(ALBUM_ID, principal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(immichService, never())
                .createSharedLink(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("open: album missing in Immich returns 404")
    void open_albumMissing() {
        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.of(event(ALBUM_ID, null, 1)));
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.openAlbum(ALBUM_ID, principal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("open: album with zero photos returns 404")
    void open_zeroPhotos() {
        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.of(event(ALBUM_ID, null, 1)));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 0)));

        assertThatThrownBy(() -> controller.openAlbum(ALBUM_ID, principal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("open: Immich share-link creation failure surfaces as 502")
    void open_shareCreationFails() {
        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.of(event(ALBUM_ID, null, 1)));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 5)));
        when(immichService.createSharedLink(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.openAlbum(ALBUM_ID, principal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }

    @Test
    @DisplayName("open: missing username falls back to snowflake in description")
    void open_missingUsernameFallback() {
        OAuth2User p = mock(OAuth2User.class);
        when(p.getAttribute("id")).thenReturn(SNOWFLAKE);
        when(p.getAttribute("username")).thenReturn(null);

        when(eventRepository.findGalleryEventByAlbumIdForUser(ALBUM_ID, SNOWFLAKE))
                .thenReturn(Optional.of(event(ALBUM_ID, null, 1)));
        when(immichService.getAlbumDetails(ALBUM_ID))
                .thenReturn(Optional.of(new ImmichAlbumResponse(ALBUM_ID, "n", "t", 5)));
        when(immichService.createSharedLink(
                        org.mockito.ArgumentMatchers.eq(ALBUM_ID),
                        org.mockito.ArgumentMatchers.startsWith(SNOWFLAKE + "-"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("k"));
        when(immichService.getShareUrl("k")).thenReturn("https://immich.example.com/share/k");

        Map<String, String> resp = controller.openAlbum(ALBUM_ID, p);
        assertThat(resp.get("url")).isEqualTo("https://immich.example.com/share/k");
    }
}
