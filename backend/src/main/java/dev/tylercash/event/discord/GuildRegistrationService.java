package dev.tylercash.event.discord;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class GuildRegistrationService {
    private final GuildRepository guildRepository;
    private final DiscordInitializationService discordInitializationService;
    private final GuildEmojiResolver guildEmojiResolver;

    public GuildRegistrationService(
            GuildRepository guildRepository,
            @Lazy DiscordInitializationService discordInitializationService,
            GuildEmojiResolver guildEmojiResolver) {
        this.guildRepository = guildRepository;
        this.discordInitializationService = discordInitializationService;
        this.guildEmojiResolver = guildEmojiResolver;
    }

    @Transactional
    public void onboard(net.dv8tion.jda.api.entities.Guild jdaGuild) {
        long id = jdaGuild.getIdLong();
        Guild row = guildRepository.findById(id).orElseGet(() -> Guild.withDefaults(id));
        if (!row.isActive()) {
            log.info("Reactivating dormant guild row {}", id);
        }
        row.setActive(true);
        guildRepository.save(row);

        guildEmojiResolver.resolve(jdaGuild, row);
        try {
            discordInitializationService.initialise(jdaGuild, row);
        } catch (Exception e) {
            log.error("Init failed for guild {}: {}", id, e.getMessage(), e);
        }
    }

    @Transactional
    public void deactivate(long guildId) {
        guildRepository.findById(guildId).ifPresent(row -> {
            row.setActive(false);
            guildRepository.save(row);
        });
        guildEmojiResolver.evict(guildId);
        log.info("Marked guild {} as inactive", guildId);
    }
}
