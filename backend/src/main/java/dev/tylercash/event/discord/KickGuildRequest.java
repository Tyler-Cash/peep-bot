package dev.tylercash.event.discord;

import jakarta.validation.constraints.NotBlank;

public record KickGuildRequest(@NotBlank String confirmGuildName) {}
