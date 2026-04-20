package dev.tylercash.event.rewind;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.sql.SQLException;
import org.postgresql.util.PGobject;

@Converter
public class VectorConverter implements AttributeConverter<String, Object> {

    @Override
    public Object convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        PGobject pgo = new PGobject();
        pgo.setType("vector");
        try {
            pgo.setValue(attribute);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Invalid vector value: " + attribute, e);
        }
        return pgo;
    }

    @Override
    public String convertToEntityAttribute(Object dbData) {
        return dbData == null ? null : dbData.toString();
    }
}
