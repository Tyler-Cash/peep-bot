package dev.tylercash.event.discord.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
    private String username;
    private Instant updatedAt;

    @Column(columnDefinition = "bytea")
    private byte[] avatarBytes;

    private String avatarContentType;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "discord_user_guild", joinColumns = @jakarta.persistence.JoinColumn(name = "snowflake"))
    @jakarta.persistence.Column(name = "guild_id")
    private java.util.Set<Long> guildIds = new java.util.HashSet<>();
}
