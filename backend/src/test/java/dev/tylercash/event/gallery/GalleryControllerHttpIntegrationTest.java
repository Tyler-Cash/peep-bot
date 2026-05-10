package dev.tylercash.event.gallery;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.immich.ImmichAlbumResponse;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

class GalleryControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private String VIEWER;
    private String OTHER;
    private long GUILD_A;
    private long GUILD_B;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        VIEWER = TestIds.nextSnowflake();
        OTHER = TestIds.nextSnowflake();
        GUILD_A = TestIds.nextLong();
        GUILD_B = TestIds.nextLong();
    }

    private static final String ALBUM_ID = "album-gallery-1";

    @Autowired
    private dev.tylercash.event.db.repository.EventRepository eventRepository;

    /** Insert a guild row with the given immich_enabled value. */
    private void seedGuild(long guildId, boolean immichEnabled) {
        jdbc.execute("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled,"
                + " rewind_enabled)"
                + " VALUES ("
                + guildId
                + ", 'events', 'event-organiser', '✅', '❌', '❓', NOW(), true, "
                + immichEnabled
                + ", false, false)"
                + " ON CONFLICT (guild_id) DO UPDATE SET immich_enabled = EXCLUDED.immich_enabled");
    }

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
    void member_immichFeatureOff_returnsEmptyList() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        // Guild row exists but immich_enabled = false
        seedGuild(GUILD_A, false);

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content().json("[]"));
    }

    @Test
    void member_listAlbums_returnsAlbums() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        seedGuild(GUILD_A, true);
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Gallery Event");

        // Attach an Immich album ID to the event via the repository
        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

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
        seedGuild(GUILD_A, true);
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Gallery Event Outage");

        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

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
        // Seed event and record attendance so findGalleryEventByAlbumIdForUser returns a result
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Thumbnail Event");
        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

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
    void nonAttendee_thumbnail_returns404() throws Exception {
        // OTHER is a member of GUILD_A but did NOT attend the event
        fixtures.registerMember(OTHER, GUILD_A, "Other", "other");
        fixtures.registerMember(VIEWER, GUILD_A, "Viewer", "viewer");
        UUID eventId = fixtures.seedEvent(GUILD_A, VIEWER, "Non-Attendee Thumbnail Event");
        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/thumbnail/{albumId}", ALBUM_ID)
                        .with(authedAs(OTHER)))
                .andExpect(status().isNotFound());
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

        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

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

        eventRepository.updateImmichAlbumId(eventId, ALBUM_ID);

        mockMvc.perform(MockMvcRequestBuilders.get("/gallery/{albumId}/open", ALBUM_ID)
                        .with(authedAs(OTHER)))
                .andExpect(status().isNotFound());
    }
}
