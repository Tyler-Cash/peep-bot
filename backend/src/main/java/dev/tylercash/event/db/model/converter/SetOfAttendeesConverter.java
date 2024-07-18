package dev.tylercash.event.db.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.db.model.Attendee;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.SneakyThrows;

import java.util.HashSet;
import java.util.Set;

@Converter
public class SetOfAttendeesConverter implements AttributeConverter<Set<Attendee>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SneakyThrows
    @Override
    public String convertToDatabaseColumn(Set<Attendee> attribute) {
        return MAPPER.writeValueAsString(attribute);
    }

    @SneakyThrows
    @Override
    public Set<Attendee> convertToEntityAttribute(String data) {
        return MAPPER.readValue(data, MAPPER.getTypeFactory().constructCollectionType(HashSet.class, Attendee.class));
    }
}