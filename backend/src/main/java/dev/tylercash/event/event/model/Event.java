package dev.tylercash.event.event.model;

import dev.tylercash.event.event.model.converter.SetOfAttendeesConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Data
@Table
@Schema
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Event {
    @Id
    @GeneratedValue
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private UUID id;
    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long messageId;
    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long serverId;
    @NotNull
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private long channelId;
    @NotNull
    private String name;
    private String description = "";
    private String location = "";
    private Integer capacity = 0;
    private Integer cost = 0;
    @Convert(converter = SetOfAttendeesConverter.class)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> accepted = new HashSet<>();
    @Convert(converter = SetOfAttendeesConverter.class)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> declined = new HashSet<>();
    @Convert(converter = SetOfAttendeesConverter.class)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private Set<Attendee> maybe = new HashSet<>();
    @Enumerated(EnumType.STRING)
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private EventState state = EventState.PLANNED;
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime dateTime;

    public Event(long messageId, long serverId, long channelId, String name, String description, LocalDateTime dateTime) {
        super();
        this.messageId = messageId;
        this.serverId = serverId;
        this.channelId = channelId;
        this.name = name;
        this.description = description;
        this.dateTime = dateTime;
    }

    public Event(EventDto event) {
        super();
        this.name = event.getName();
        this.description = event.getDescription();
        this.location = event.getLocation();
        this.capacity = event.getCapacity();
        this.cost = event.getCost();
        this.dateTime = LocalDateTime.from(event.getDateTime());
    }

    public void setDateTime(@NotNull LocalDateTime dateTime) {
        dateTime.truncatedTo(ChronoUnit.MINUTES);
        this.dateTime = dateTime;
    }

    public Integer getCapacity() {
        int output;
        if (capacity == 0) {
            output = accepted.size();
        } else {
            output = capacity;
        }
        return output;

    }
}
