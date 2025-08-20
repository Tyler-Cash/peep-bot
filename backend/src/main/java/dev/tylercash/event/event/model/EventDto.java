package dev.tylercash.event.event.model;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDto {
    public static final int NAME_SIZE_MIN = 4;
    public static final int NAME_SIZE_MAX = 80;
    public static final String NAME_SIZE_ERROR = "Name should be between " + NAME_SIZE_MIN + " and " + NAME_SIZE_MAX + " characters long";
    private UUID id;

    @NotBlank(message = "Name is required.")
    @Size(min = NAME_SIZE_MIN, message = NAME_SIZE_ERROR)
    @Size(max = NAME_SIZE_MAX, message = NAME_SIZE_ERROR)
    private String name;

    @Size(max = 3800, message = "Please make this less than 3800 characters long")
    private String description = "";

    private String location = "";

    @PositiveOrZero(message = "Capacity must be positive.")
    private Integer capacity = 0;

    @PositiveOrZero(message = "Cost must be positive.")
    private Integer cost = 0;

    @NotNull(message = "A date and time is required.")
    @FutureOrPresent(message = "The event can only be organized in the future.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private ZonedDateTime dateTime;

    // Transient request-only flag: whether to notify people when creating the event. Defaults to true.
    private Boolean notifyOnCreate = true;

    public EventDto(Event event) {
        this.id = event.getId();
        this.name = event.getName();
        this.description = event.getDescription();
        this.location = event.getLocation();
        this.capacity = event.getCapacity();
        this.cost = event.getCost();
        this.dateTime = event.getDateTime();
        // Do not expose notifyOnCreate from entity; it's request-only and not persisted
    }
}
