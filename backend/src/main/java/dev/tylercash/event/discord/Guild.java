package dev.tylercash.event.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guild")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Guild {

    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "events_role", nullable = false)
    private String eventsRole;

    @Column(name = "admin_role", nullable = false)
    private String adminRole;

    @Column(name = "separator_channel")
    private String separatorChannel;

    @Column(name = "emoji_accepted", nullable = false)
    private String emojiAccepted;

    @Column(name = "emoji_declined", nullable = false)
    private String emojiDeclined;

    @Column(name = "emoji_maybe", nullable = false)
    private String emojiMaybe;

    @Column(name = "primary_location_place_id")
    private String primaryLocationPlaceId;

    @Column(name = "primary_location_name")
    private String primaryLocationName;

    @Column(name = "primary_location_lat")
    private Double primaryLocationLat;

    @Column(name = "primary_location_lng")
    private Double primaryLocationLng;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    public static Guild withDefaults(long guildId) {
        Guild g = new Guild();
        g.setGuildId(guildId);
        g.setEventsRole("events");
        g.setAdminRole("event-admin");
        g.setEmojiAccepted("✅");
        g.setEmojiDeclined("❌");
        g.setEmojiMaybe("❓");
        g.setJoinedAt(Instant.now());
        g.setActive(true);
        return g;
    }
}
