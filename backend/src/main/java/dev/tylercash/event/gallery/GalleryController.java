package dev.tylercash.event.gallery;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.AttendeeDto;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichAlbumResponse;
import dev.tylercash.event.immich.ImmichService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/gallery")
@Tag(name = "Gallery", description = "Photo album gallery for attended events")
public class GalleryController {

    /** Per-user share-link TTL — same TTL applied to the BFF cache header. */
    static final Duration USER_SHARE_TTL = Duration.ofDays(7);

    private final EventRepository eventRepository;
    private final ImmichService immichService;
    private final GuildMembershipService guildMembershipService;
    private final Clock clock;

    @Operation(summary = "List gallery albums", description = "Returns albums for events the user attended")
    @GetMapping
    public List<GalleryAlbumDto> getGallery(@RequestParam long guildId, @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        guildMembershipService.assertMember(snowflake, guildId);

        List<Event> events = eventRepository.findGalleryEventsForUser(guildId, snowflake);
        return events.stream()
                .map(event -> {
                    String albumId = event.getImmichAlbumId();
                    Optional<ImmichAlbumResponse> albumOpt = immichService.getAlbumDetails(albumId);
                    // Skip albums that no longer exist in Immich (deleted, server gone, etc.)
                    // and albums that have zero photos uploaded — nothing to show.
                    int assetCount =
                            albumOpt.map(ImmichAlbumResponse::assetCount).orElse(0);
                    if (albumOpt.isEmpty() || assetCount == 0) {
                        return null;
                    }
                    String thumbnailUrl = "/api/gallery/thumbnail/" + albumId;
                    // The frontend always opens albums via the BFF "/open" redirect so we
                    // generate a fresh per-user, time-limited share each cache miss.
                    String albumUrl = "/api/gallery/" + albumId + "/open";
                    List<AttendeeDto> attendees = event.getAccepted().stream()
                            .sorted(Comparator.comparing(
                                    a -> a.getInstant() != null ? a.getInstant() : Instant.EPOCH))
                            .map(AttendeeDto::new)
                            .toList();
                    return new GalleryAlbumDto(
                            event.getId(),
                            event.getName(),
                            event.getDateTime(),
                            albumId,
                            thumbnailUrl,
                            albumUrl,
                            assetCount,
                            attendees);
                })
                .filter(dto -> dto != null)
                .toList();
    }

    @Operation(
            summary = "Get album thumbnail",
            description = "Streams the album cover thumbnail from Immich on demand — never buffered to disk or DB")
    @GetMapping("/thumbnail/{albumId}")
    public ResponseEntity<StreamingResponseBody> getAlbumThumbnail(
            @PathVariable String albumId, @AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        Optional<ImmichAlbumResponse> albumOpt = immichService.getAlbumDetails(albumId);
        if (albumOpt.isEmpty() || albumOpt.get().albumThumbnailAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No thumbnail available");
        }
        String assetId = albumOpt.get().albumThumbnailAssetId();

        StreamingResponseBody body = out -> {
            if (!immichService.streamThumbnail(assetId, out)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found");
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .body(body);
    }

    /**
     * Per-user album share resolver. Creates a fresh 1-week Immich share link
     * labelled with {@code <username>-<isoTimestamp>} so concurrent users don't
     * collide and returns the resulting Immich share URL as JSON. The BFF
     * (Next.js on Vercel) wraps this and emits the 302 redirect + browser cache
     * headers — Spring sits behind no CDN, so caching responsibility lives on
     * the BFF where it can leverage Vercel's edge.
     */
    @Operation(
            summary = "Resolve an album's share URL for the current user",
            description = "Generates a per-user 1-week Immich share link and returns its public URL as JSON")
    @GetMapping("/{albumId}/open")
    public Map<String, String> openAlbum(
            @PathVariable String albumId, @AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String snowflake = principal.getAttribute("id");
        if (snowflake == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        // Authz: only users who accepted the event may open its album.
        Optional<Event> eventOpt = eventRepository.findGalleryEventByAlbumIdForUser(albumId, snowflake);
        if (eventOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not accessible");
        }

        // Confirm the album still exists in Immich and has photos. The list
        // endpoint already filters these out, but stale caches could still send
        // requests for albums that have since emptied or been deleted.
        Optional<ImmichAlbumResponse> albumOpt = immichService.getAlbumDetails(albumId);
        if (albumOpt.isEmpty() || albumOpt.get().assetCount() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not available");
        }

        String username = principal.getAttribute("username");
        if (username == null || username.isBlank()) {
            username = snowflake;
        }
        Instant now = clock.instant();
        String description = username + "-" + now.truncatedTo(ChronoUnit.SECONDS).toString();
        Instant expiresAt = now.plus(USER_SHARE_TTL);

        Optional<String> shareKey = immichService.createSharedLink(albumId, description, expiresAt);
        if (shareKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create share link");
        }

        return Map.of(
                "url", immichService.getShareUrl(shareKey.get()),
                "expiresAt", expiresAt.toString());
    }
}
