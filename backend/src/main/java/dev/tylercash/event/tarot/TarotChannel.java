package dev.tylercash.event.tarot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A channel /tarot is allowed in. A guild with no rows allows every channel. */
@Entity
@Table(name = "tarot_channel")
@IdClass(TarotChannel.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TarotChannel {
    @Id
    @Column(name = "guild_id")
    private long guildId;

    @Id
    @Column(name = "channel_id")
    private long channelId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private long guildId;
        private long channelId;
    }
}
