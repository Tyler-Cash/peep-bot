package dev.tylercash.event.gallery;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.immich.ImmichAlbumResponse;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GalleryControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String VIEWER = "501";
    private static final String OTHER = "502";
    private static final long GUILD_A = 4001L;
    private static final long GUILD_B = 4002L;
    private static final String ALBUM_ID = "album-gallery-1";

    @Autowired
    private dev.tylercash.event.db.repository.EventRepository eventRepository;

    // -----------------------------------------------------------------------
    // GET /gallery?guildId=N
    // -----------------------------------------------------------------------

    @Test
    void anonymous_listAlbums_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/gallery").param("guildId", String.valueOf(GUILD_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonMember_listAlbums_returns403() throws Exception {
        // VIEWER is only in GUILD_B; querying GUILD_A → 403
        fixtures.registerMember(VIEWER, GUILD_B, "Viewer", "viewer");

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_listAlbums_returnsAlbums() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Gallery Event");

        // Attach an Immich album ID to the event via the repository
        eventRepository.findById(eventId).ifPresent(event -> {
            event.setImmichAlbumId(ALBUM_ID);
            eventRepository.save(event);
        });

        ImmichAlbumResponse album = new ImmichAlbumResponse(ALBUM_ID, "Gallery Event", "asset-thumb-1", 5);
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.of(album));

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].albumId").value(ALBUM_ID))
                .andExpect(jsonPath("$[0].assetCount").value(5));
    }

    @Test
    void member_listAlbums_immichOutage_returns502() throws Exception {
        // All events have album IDs but every Immich lookup fails → 502
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Gallery Event Outage");

        eventRepository.findById(eventId).ifPresent(event -> {
            event.setImmichAlbumId(ALBUM_ID);
            eventRepository.save(event);
        });

        // getAlbumDetails returning empty simulates Immich being unreachable
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.empty());

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(VIEWER)))
                .andExpect(status().isBadGateway());
    }

    // -----------------------------------------------------------------------
    // GET /gallery/thumbnail/{albumId}
    // -----------------------------------------------------------------------

    @Test
    void anonymous_thumbnail_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/thumbnail/{albumId}", ALBUM_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void member_thumbnail_returnsBytes() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");

        String assetId = "asset-thumb-1";
        ImmichAlbumResponse album = new ImmichAlbumResponse(ALBUM_ID, "Gallery Event", assetId, 3);
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.of(album));
        // Simulate streaming bytes into the OutputStream
        doAnswer(invocation -> {
                    java.io.OutputStream out = invocation.getArgument(1);
                    out.write(new byte[] {0x01, 0x02, 0x03});
                    return true;
                })
                .when(immichService)
                .streamThumbnail(eq(assetId), any());

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/thumbnail/{albumId}", ALBUM_ID)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/jpeg"));
    }

    @Test
    void thumbnail_immichNotFound_returns404() throws Exception {
        // getAlbumDetails returns empty → controller throws 404
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.empty());

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/thumbnail/{albumId}", ALBUM_ID)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thumbnail_noThumbnailAssetId_returns404() throws Exception {
        // Album exists but has no albumThumbnailAssetId → controller throws 404
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        ImmichAlbumResponse album = new ImmichAlbumResponse(ALBUM_ID, "Gallery Event", null, 3);
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.of(album));

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/thumbnail/{albumId}", ALBUM_ID)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // GET /gallery/{albumId}/open
    // -----------------------------------------------------------------------

    @Test
    void anonymous_open_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/{albumId}/open", ALBUM_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void member_open_returnsLink() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Open Album Event");

        eventRepository.findById(eventId).ifPresent(event -> {
            event.setImmichAlbumId(ALBUM_ID);
            eventRepository.save(event);
        });

        ImmichAlbumResponse album = new ImmichAlbumResponse(ALBUM_ID, "Open Album Event", "asset-1", 4);
        when(immichService.getAlbumDetails(ALBUM_ID)).thenReturn(Optional.of(album));
        when(immichService.createSharedLink(eq(ALBUM_ID), anyString(), any(Instant.class)))
                .thenReturn(Optional.of("share-key-abc"));
        when(immichService.getShareUrl("share-key-abc")).thenReturn("https://immich.example.com/share/share-key-abc");

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/{albumId}/open", ALBUM_ID)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://immich.example.com/share/share-key-abc"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void nonAttendee_open_returns404() throws Exception {
        // OTHER user never attended the event — findGalleryEventByAlbumIdForUser returns empty → 404
        fixtures.registerMember(OTHER, GUILD_A, "Other", "other");
        // Seed event attended only by VIEWER, not OTHER
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Private Album Event");

        eventRepository.findById(eventId).ifPresent(event -> {
            event.setImmichAlbumId(ALBUM_ID);
            eventRepository.save(event);
        });

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/{albumId}/open", ALBUM_ID)
                        .with(authedAs(OTHER)))
                .andExpect(status().isNotFound());
    }
}
