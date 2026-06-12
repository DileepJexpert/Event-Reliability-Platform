package com.eventreliability.common;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Thin wrapper over the Spring-managed Jackson {@link ObjectMapper} for converting domain records
 * to/from the {@code byte[]} values stored on Kafka topics. The platform serialises its own control
 * records as JSON (no Schema Registry — §4/§12); opaque business payloads are never deserialised.
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] toBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise " + value.getClass().getSimpleName(), ex);
        }
    }

    public String toJson(Object value) {
        return new String(toBytes(value), StandardCharsets.UTF_8);
    }

    public <T> T fromBytes(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialise " + type.getSimpleName(), ex);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
