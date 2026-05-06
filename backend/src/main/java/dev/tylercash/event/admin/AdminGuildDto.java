package dev.tylercash.event.admin;

public record AdminGuildDto(
        String guildId,
        String name,
        boolean active,
        boolean immichEnabled,
        boolean googleAutocompleteEnabled,
        boolean rewindEnabled,
        boolean contractsEnabled) {}
