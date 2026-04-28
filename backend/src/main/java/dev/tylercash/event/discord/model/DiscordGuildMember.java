package dev.tylercash.event.discord.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "discord_guild_member")
@IdClass(DiscordGuildMember.PK.class)
@NoArgsConstructor
@AllArgsConstructor
public class DiscordGuildMember {

    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Id
    private String snowflake;

    private String displayName;

    @Column(columnDefinition = "bytea")
    private byte[] avatarBytes;

    private String avatarContentType;

    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long guildId;
        private String snowflake;
    }
}
