package dev.tylercash.event.admin;

import dev.tylercash.event.discord.DirectoryEntry;
import java.util.List;

/**
 * Tarot config for one guild. {@code allowedChannelIds} empty means every channel; channel ids are
 * strings so JS doesn't lose snowflake precision.
 */
public record AdminTarotSettingsDto(
        List<DirectoryEntry> availableChannels, List<String> allowedChannelIds, long drawsLast7Days) {}
