package dev.tylercash.event.event.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.tylercash.event.event.model.Attendee;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.SneakyThrows;

import java.util.HashSet;
import java.util.Set;

@Converter
public class SetOfAttendeesConverter implements AttributeConverter<Set<Attendee>, String> {
    private static final ObjectMapper MAPPER = getMapper();

    private static ObjectMapper getMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

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