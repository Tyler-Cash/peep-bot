package dev.tylercash.event.discord;

import dev.tylercash.event.event.EventCreateRateLimitConfiguration;
import dev.tylercash.event.event.EventCreateRateLimiter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/guild/{guildId}/settings")
@RequiredArgsConstructor
@Tag(name = "Guild", description = "Discord guild info")
public class GuildSettingsController {

    private static final Set<Integer> ALLOWED_RATE_LIMIT_VALUES = Set.of(3, 5, 7, 10);

    private final GuildRepository guildRepository;
    private final GuildMembershipService guildMembershipService;
    private final DiscordAuthService discordAuthService;
    private final JDA jda;
    private final GuildEmojiResolver guildEmojiResolver;
    private final EventCreateRateLimiter eventCreateRateLimiter;
    private final EventCreateRateLimitConfiguration eventCreateRateLimitConfiguration;

    @GetMapping
    public GuildSettingsDto getSettings(@PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        Guild row = guildRepository.findById(guildIdLong).orElseGet(() -> Guild.withDefaults(guildIdLong));
        return GuildSettingsDto.from(row, eventCreateRateLimitConfiguration.getPerGuildPerHour());
    }

    @PatchMapping
    public GuildSettingsDto updateSettings(
            @PathVariable String guildId,
            @RequestBody GuildSettingsRequest request,
            @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        long userId = Long.parseLong(snowflake);
        if (!discordAuthService.isGuildOwner(guildIdLong, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the guild owner can edit settings");
        }
        Guild row = guildRepository.findById(guildIdLong).orElseGet(() -> Guild.withDefaults(guildIdLong));

        // primary_location_* fields are always overwritten with the request values (including null = clear)
        row.setPrimaryLocationPlaceId(request.primaryLocationPlaceId());
        row.setPrimaryLocationName(request.primaryLocationName());
        row.setPrimaryLocationLat(request.primaryLocationLat());
        row.setPrimaryLocationLng(request.primaryLocationLng());

        // Role and emoji fields keep the existing value when the request omits them or sends blank
        if (request.eventsRole() != null && !request.eventsRole().isBlank()) row.setEventsRole(request.eventsRole());
        if (request.organiserRole() != null && !request.organiserRole().isBlank())
            row.setOrganiserRole(request.organiserRole());
        // separator_channel: null clears, blank clears, anything else sets
        row.setSeparatorChannel(
                (request.separatorChannel() == null
                                || request.separatorChannel().isBlank())
                        ? null
                        : request.separatorChannel());
        if (request.emojiAccepted() != null && !request.emojiAccepted().isBlank())
            row.setEmojiAccepted(request.emojiAccepted());
        if (request.emojiDeclined() != null && !request.emojiDeclined().isBlank())
            row.setEmojiDeclined(request.emojiDeclined());
        if (request.emojiMaybe() != null && !request.emojiMaybe().isBlank()) row.setEmojiMaybe(request.emojiMaybe());

        Integer requestedLimit = request.eventCreateRateLimitPerHour();
        if (requestedLimit != null && !ALLOWED_RATE_LIMIT_VALUES.contains(requestedLimit)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "eventCreateRateLimitPerHour must be one of " + ALLOWED_RATE_LIMIT_VALUES + " or null");
        }
        row.setEventCreateRateLimitPerHour(requestedLimit);

        guildRepository.save(row);
        eventCreateRateLimiter.invalidate(guildIdLong);

        // Re-resolve emoji into the in-memory cache so the change takes effect without a restart
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild != null) {
            guildEmojiResolver.resolve(jdaGuild, row);
        }
        return GuildSettingsDto.from(row, eventCreateRateLimitConfiguration.getPerGuildPerHour());
    }
}
