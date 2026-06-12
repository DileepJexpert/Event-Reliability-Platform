package com.eventreliability.common;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generic Jackson-backed Kafka {@link Serde} for use in the Kafka Streams topologies (pattern
 * detection and materialised views). Uses plain Apache Kafka serialization only — no Confluent
 * Schema Registry serdes (§4).
 */
public final class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper mapper;
    private final Class<T> type;

    public JsonSerde(ObjectMapper mapper, Class<T> type) {
        this.mapper = mapper;
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return mapper.writeValueAsBytes(data);
            } catch (Exception ex) {
                throw new IllegalStateException("JsonSerde serialize failed for " + type.getSimpleName(), ex);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, data) -> {
            if (data == null || data.length == 0) {
                return null;
            }
            try {
                return mapper.readValue(data, type);
            } catch (Exception ex) {
                throw new IllegalStateException("JsonSerde deserialize failed for " + type.getSimpleName(), ex);
            }
        };
    }
}
