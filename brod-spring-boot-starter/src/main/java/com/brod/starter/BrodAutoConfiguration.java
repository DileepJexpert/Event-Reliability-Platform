package com.brod.starter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Auto-configuration for the Brod starter. With the dependency on the classpath and Kafka configured,
 * onboarding is two lines (the dependency + {@code spring.kafka.bootstrap-servers}):
 *
 * <ul>
 *   <li>Registers a {@link DefaultErrorHandler} backed by {@link BrodDeadLetterRecoverer}, which
 *       Spring Boot wires into the auto-configured listener factory — so exhausted retries are
 *       captured to Brod's DLQ instead of silently dropped. Opt out with {@code brod.auto-dlq=false}
 *       or by defining your own {@link CommonErrorHandler}.</li>
 *   <li>Exposes an {@link IdempotencyStore} bean (in-memory by default) to make retries/replays safe.</li>
 * </ul>
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(BrodProperties.class)
public class BrodAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore brodIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "brod", name = "auto-dlq", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(KafkaTemplate.class)
    @SuppressWarnings("unchecked")
    public BrodDeadLetterRecoverer brodDeadLetterRecoverer(
            KafkaTemplate<?, ?> template, BrodProperties props,
            @Value("${spring.application.name:}") String applicationName) {
        return new BrodDeadLetterRecoverer(
                (KafkaTemplate<Object, Object>) template, props.dlqTopic(), applicationName);
    }

    @Bean
    @ConditionalOnProperty(prefix = "brod", name = "auto-dlq", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(BrodDeadLetterRecoverer.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler brodErrorHandler(BrodDeadLetterRecoverer recoverer, BrodProperties props) {
        return new DefaultErrorHandler(recoverer, new FixedBackOff(props.backoffMs(), props.retries()));
    }
}
