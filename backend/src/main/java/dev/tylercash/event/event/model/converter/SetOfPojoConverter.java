package dev.tylercash.event.event.model.converter;

import jakarta.persistence.AttributeConverter;
import java.util.HashSet;
import java.util.Set;
import lombok.SneakyThrows;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class SetOfPojoConverter<T> implements AttributeConverter<Set<T>, String> {
    // Jackson 3 has java.time support built-in and defaults to ISO-8601 (not timestamps),
    // so the JSR-310 module registration and WRITE_DATES_AS_TIMESTAMPS=false toggle from
    // the Jackson 2 setup are no longer needed.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final Class<T> type;

    public SetOfPojoConverter(Class<T> type) {
        this.type = type;
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
