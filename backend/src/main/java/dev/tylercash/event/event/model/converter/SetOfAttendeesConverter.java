package dev.tylercash.event.event.model.converter;

import dev.tylercash.event.event.model.Attendee;
import jakarta.persistence.Converter;

@Converter
public class SetOfAttendeesConverter extends SetOfPojoConverter<Attendee> {
    public SetOfAttendeesConverter() {
        super(Attendee.class);
    }
}