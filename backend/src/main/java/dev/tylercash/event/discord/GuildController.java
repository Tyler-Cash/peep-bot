package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.awt.Color;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guild")
@RequiredArgsConstructor
@Tag(name = "Guild", description = "Discord guild info")
public class GuildController {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;

    @GetMapping
    public List<GuildDto> getGuilds() {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        if (guild == null) {
            return List.of();
        }
        return List.of(toDto(guild));
    }

    private GuildDto toDto(Guild guild) {
        String name = guild.getName();
        return new GuildDto(
                guild.getId(),
                name,
                deriveInitials(name),
                guild.getIconUrl(),
                deriveColor(guild.getId()),
                discordConfiguration.getSeperatorChannel(),
                guild.getMemberCount());
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
