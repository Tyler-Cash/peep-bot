package dev.tylercash.event.event.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import lombok.SneakyThrows;

import java.util.HashSet;
import java.util.Set;


public class SetOfPojoConverter<T> implements AttributeConverter<Set<T>, String> {
    private static final ObjectMapper MAPPER = getMapper();
    private final Class<T> type;

    public SetOfPojoConverter(Class<T> type) {
        this.type = type;
    }

    private static ObjectMapper getMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    @SneakyThrows
    @Override
    public String convertToDatabaseColumn(Set<T> attribute) {
        return MAPPER.writeValueAsString(attribute);
    }

    @SneakyThrows
    @Override
    public Set<T> convertToEntityAttribute(String data) {
        return MAPPER.readValue(data, MAPPER.getTypeFactory().constructCollectionType(HashSet.class, type));
    }
}