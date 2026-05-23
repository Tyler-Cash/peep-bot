package dev.tylercash.event.discord;

import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.awt.Color;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/guild")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Guild", description = "Discord guild info")
public class GuildController {
    private final JDA jda;
    private final GuildMembershipService guildMembershipService;
    private final GuildRepository guildRepository;
    private final DiscordAuthService discordAuthService;
    private final ObservationRegistry observationRegistry;

    @GetMapping
    public List<GuildDto> getGuilds(@AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        List<Long> userGuildIds = guildMembershipService.getGuildIdsForUser(snowflake);
        return userGuildIds.stream()
                .map(jda::getGuildById)
                .filter(Objects::nonNull)
                .map(this::toDto)
                .toList();
    }

    @DeleteMapping("/{guildId}")
    public ResponseEntity<Void> kick(
            @PathVariable String guildId,
            @Valid @RequestBody KickGuildRequest body,
            @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        long userId = Long.parseLong(snowflake);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        if (!discordAuthService.isGuildOwner(guildIdLong, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the guild owner can remove peepbot");
        }
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Guild not found");
        }
        String expected = jdaGuild.getName().trim();
        String supplied = body.confirmGuildName().trim();
        if (!expected.equalsIgnoreCase(supplied)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "guild name confirmation does not match");
        }
        log.info("AUDIT user {} kicking peepbot from guild {} ({})", snowflake, guildIdLong, expected);
        JdaObservations.queue(jdaGuild.leave(), "discord.guild.leave.queue", observationRegistry);
        return ResponseEntity.noContent().build();
    }

    private GuildDto toDto(net.dv8tion.jda.api.entities.Guild guild) {
        String name = guild.getName();
        dev.tylercash.event.discord.Guild settings =
                guildRepository.findById(Long.parseLong(guild.getId())).orElse(null);
        return new GuildDto(
                guild.getId(),
                name,
                deriveInitials(name),
                guild.getIconUrl(),
                deriveColor(guild.getId()),
                guild.getMemberCount(),
                settings != null ? settings.getPrimaryLocationLat() : null,
                settings != null ? settings.getPrimaryLocationLng() : null);
    }

    private static String deriveInitials(String name) {
        String[] words = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        if (words.length >= 2) {
            for (String word : words) {
                if (!word.isEmpty()) {
                    sb.appendCodePoint(word.codePointAt(0));
                }
                if (sb.length() >= 2) break;
            }
        } else {
            String word = words[0];
            sb.append(word, 0, Math.min(2, word.length()));
        }
        return sb.toString().toUpperCase();
    }

    private static String deriveColor(String guildId) {
        long id = Long.parseLong(guildId);
        float hue = (Math.abs(id) % 360) / 360.0f;
        Color color = Color.getHSBColor(hue, 0.45f, 0.95f);
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
