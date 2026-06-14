package com.eventreliability.config;

import java.util.List;

import com.eventreliability.domain.FailureHeaders;

import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Component;

/**
 * Resolves the inbound correlation id from the configured header alias(es) (§6.3). The header name is
 * not hard-coded, so an onboarding org can keep emitting the correlation id under a name their apps
 * already use (e.g. {@code correlationId}); the platform's native {@code x-correlation-id} is always
 * retained as a fallback alias (see {@link ReliabilityProperties.Headers}). This is the single place
 * the header contract's id is interpreted, mirroring {@link TopicNames} for topic names.
 */
@Component
public class CorrelationIdResolver {

    private final List<String> aliases;

    public CorrelationIdResolver(ReliabilityProperties props) {
        this.aliases = props.headers().correlationId();
    }

    /** The first non-blank correlation id found among the configured header aliases, or {@code null}. */
    public String fromHeaders(Headers headers) {
        for (String name : aliases) {
            String value = FailureHeaders.getString(headers, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** The configured header aliases, in priority order (always includes {@code x-correlation-id}). */
    public List<String> aliases() {
        return aliases;
    }
}
