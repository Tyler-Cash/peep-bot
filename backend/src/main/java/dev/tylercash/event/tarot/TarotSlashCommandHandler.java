package dev.tylercash.event.tarot;

import dev.tylercash.event.discord.Feature;
import dev.tylercash.event.discord.FeatureFlagService;
import dev.tylercash.event.discord.JdaObservations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Handles {@code /tarot}. Gate checks and the "already drew today" lookup run on the JDA thread
 * and answer with an ephemeral reply; a fresh draw defers, then draws and renders on the
 * listener executor.
 */
@Slf4j
@Component
public class TarotSlashCommandHandler {

    public static final String COMMAND = "tarot";
    private static final Color EMBED_COLOUR = new Color(0x7B5CD6);

    private final TarotService tarotService;
    private final TarotDeck deck;
    private final FeatureFlagService featureFlagService;
    private final ObservationRegistry observationRegistry;
    private final Executor executor;

    public TarotSlashCommandHandler(
            TarotService tarotService,
            TarotDeck deck,
            FeatureFlagService featureFlagService,
            ObservationRegistry observationRegistry,
            @Qualifier("discordListenerExecutor") Executor executor) {
        this.tarotService = tarotService;
        this.deck = deck;
        this.featureFlagService = featureFlagService;
        this.observationRegistry = observationRegistry;
        this.executor = executor;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            replyEphemeral(event, "Tarot draws only work inside a server.");
            return;
        }
        long guildId = event.getGuild().getIdLong();
        if (!featureFlagService.isEnabled(guildId, Feature.TAROT)) {
            replyEphemeral(event, "Tarot draws are not enabled in this server.");
            return;
        }
        if (!tarotService.isChannelAllowed(guildId, event.getChannelIdLong())) {
            replyEphemeral(event, "Tarot draws aren't available here. Try " + channelMentions(guildId) + ".");
            return;
        }
        long userId = event.getUser().getIdLong();
        var existing = tarotService.todaysDraw(guildId, userId);
        if (existing.isPresent()) {
            replyEphemeral(event, alreadyDrawnMessage(existing.get()));
            return;
        }

        Observation parent = Observation.createNotStarted("discord.tarot-draw", observationRegistry)
                .start();
        try (Observation.Scope ignored = parent.openScope()) {
            JdaObservations.queue(event.deferReply(), "discord.defer-reply.queue", observationRegistry);
            executor.execute(() -> {
                try (Observation.Scope inner = parent.openScope()) {
                    drawAndReply(event, guildId, userId);
                } catch (RuntimeException e) {
                    parent.error(e);
                    log.error("Tarot draw failed for user {} in guild {}", userId, guildId, e);
                    JdaObservations.queue(
                            event.getHook().editOriginal("The cards are clouded right now. Try again shortly."),
                            "discord.hook.edit-original.queue",
                            observationRegistry);
                } finally {
                    parent.stop();
                }
            });
        }
    }

    private void drawAndReply(SlashCommandInteractionEvent event, long guildId, long userId) {
        TarotService.DrawResult result = tarotService.drawToday(guildId, userId);
        if (result.alreadyDrawn()) {
            // Lost a race with a concurrent /tarot from the same user.
            JdaObservations.queue(
                    event.getHook().editOriginal(alreadyDrawnMessage(result)),
                    "discord.hook.edit-original.queue",
                    observationRegistry);
            return;
        }
        TarotCard card = result.card();
        boolean reversed = result.draw().isReversed();
        String displayName = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getUser().getEffectiveName();
        JdaObservations.queue(
                event.getHook()
                        .editOriginalEmbeds(buildEmbed(
                                card,
                                reversed,
                                result,
                                displayName,
                                event.getUser().getEffectiveAvatarUrl()))
                        .setFiles(FileUpload.fromData(deck.image(card, reversed), card.imageFileName())),
                "discord.hook.edit-original.queue",
                observationRegistry);
    }

    static MessageEmbed buildEmbed(
            TarotCard card, boolean reversed, TarotService.DrawResult result, String displayName, String avatarUrl) {
        String title = card.name() + (reversed ? " (Reversed)" : "");
        String keywords = String.join(" · ", card.keywords());
        return new EmbedBuilder()
                .setAuthor(displayName + "'s daily draw", null, avatarUrl)
                .setTitle(title)
                .setDescription(
                        "*" + keywords + "*\n\n" + result.reading() + "\n\nNext draw " + discordRelative(result))
                .setImage("attachment://" + card.imageFileName())
                .setColor(EMBED_COLOUR)
                .setFooter(TarotDeck.ATTRIBUTION)
                .build();
    }

    static String alreadyDrawnMessage(TarotService.DrawResult result) {
        String name = result.card().name() + (result.draw().isReversed() ? " (Reversed)" : "");
        return "You've already drawn today: **" + name + "**\n> " + result.reading() + "\nYour next draw is "
                + discordRelative(result) + ".";
    }

    private static String discordRelative(TarotService.DrawResult result) {
        return "<t:" + result.nextReset().getEpochSecond() + ":R>";
    }

    private String channelMentions(long guildId) {
        List<Long> ids = tarotService.allowedChannelIds(guildId);
        return ids.stream().map(id -> "<#" + id + ">").collect(Collectors.joining(", "));
    }

    private void replyEphemeral(SlashCommandInteractionEvent event, String message) {
        JdaObservations.queue(event.reply(message).setEphemeral(true), "discord.reply.queue", observationRegistry);
    }
}
