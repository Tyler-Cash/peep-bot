package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class VectorConverterTest {
    private final VectorConverter converter = new VectorConverter();

    @Test
    @DisplayName("convertToDatabaseColumn wraps the attribute in a PGobject with type=vector")
    void convertToDatabaseColumn_producesVectorPgObject() {
        Object result = converter.convertToDatabaseColumn("[1.0,2.0,3.0]");

        assertThat(result).isInstanceOf(PGobject.class);
        PGobject pgo = (PGobject) result;
        assertThat(pgo.getType()).isEqualTo("vector");
        assertThat(pgo.getValue()).isEqualTo("[1.0,2.0,3.0]");
    }

    @Test
    @DisplayName("convertToDatabaseColumn passes null through as null")
    void convertToDatabaseColumn_nullPassesThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("convertToEntityAttribute returns the object's string representation")
    void convertToEntityAttribute_usesToString() {
        PGobject pgo = new PGobject();
        pgo.setType("vector");
        try {
            pgo.setValue("[0.5,0.25]");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(converter.convertToEntityAttribute(pgo)).isEqualTo("[0.5,0.25]");
    }

    @Test
    @DisplayName("convertToEntityAttribute passes null through as null")
    void convertToEntityAttribute_nullPassesThrough() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
