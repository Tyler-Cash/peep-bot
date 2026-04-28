package dev.tylercash.event.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guild_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuildSettings {

    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "primary_location_place_id")
    private String primaryLocationPlaceId;

    @Column(name = "primary_location_name")
    private String primaryLocationName;

    @Column(name = "primary_location_lat")
    private Double primaryLocationLat;

    @Column(name = "primary_location_lng")
    private Double primaryLocationLng;
}
