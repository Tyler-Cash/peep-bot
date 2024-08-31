package dev.tylercash.event.event.model;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
    private UUID id;

    @NotBlank(message = "Name is required.")
    private String name;

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

    public EventDto(Event event) {
        this.id = event.getId();
        this.name = event.getName();
        this.description = event.getDescription();
        this.location = event.getLocation();
        this.capacity = event.getCapacity();
        this.cost = event.getCost();
        this.dateTime = event.getDateTime();
    }
}
