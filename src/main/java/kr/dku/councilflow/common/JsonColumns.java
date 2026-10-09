package kr.dku.councilflow.common;

import jakarta.persistence.AttributeConverter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** MySQL JSON 컬럼 ↔ 자바 값 변환기 모음. 엔티티에서 @Convert(converter = ...) 로 쓴다. */
public final class JsonColumns {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonColumns() {
    }

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, TypeReference<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, Class<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static class LongList implements AttributeConverter<List<Long>, String> {
        @Override
        public String convertToDatabaseColumn(List<Long> attribute) {
            return write(attribute == null ? List.of() : attribute);
        }

        @Override
        public List<Long> convertToEntityAttribute(String dbData) {
            List<Long> value = read(dbData, new TypeReference<List<Long>>() { });
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    public static class StringList implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return write(attribute == null ? List.of() : attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            List<String> value = read(dbData, new TypeReference<List<String>>() { });
            return value == null ? List.of() : List.copyOf(value);
        }
    }
}
