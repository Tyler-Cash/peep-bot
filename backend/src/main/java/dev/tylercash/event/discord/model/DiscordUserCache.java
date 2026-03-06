package dev.tylercash.event.discord.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "discord_user_cache")
@NoArgsConstructor
@AllArgsConstructor
public class DiscordUserCache {
    @Id
    private String snowflake;

    private String displayName;
    private Instant updatedAt;
}
