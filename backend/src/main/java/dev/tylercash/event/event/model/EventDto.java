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

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDto {
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
}
