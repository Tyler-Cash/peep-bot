package dev.tylercash.event.discord;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildSettingsRepository extends JpaRepository<GuildSettings, Long> {}
