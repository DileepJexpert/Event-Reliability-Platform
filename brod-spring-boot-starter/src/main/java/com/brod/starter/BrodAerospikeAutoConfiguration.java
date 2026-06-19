package com.brod.starter;

import java.util.Arrays;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires an Aerospike-backed {@link IdempotencyStore} when {@code brod.idempotency.store=aerospike} and
 * the Aerospike client is on the classpath. Runs before {@link BrodAutoConfiguration} so its store wins
 * over the in-memory default. If the app already exposes an {@link IAerospikeClient} bean, that one is
 * reused; otherwise one is created from {@code brod.idempotency.aerospike.*}.
 */
@AutoConfiguration(before = BrodAutoConfiguration.class)
@ConditionalOnClass(IAerospikeClient.class)
@ConditionalOnProperty(prefix = "brod.idempotency", name = "store", havingValue = "aerospike")
@EnableConfigurationProperties(BrodProperties.class)
public class BrodAerospikeAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IAerospikeClient.class)
    public IAerospikeClient brodAerospikeClient(BrodProperties props) {
        return new AerospikeClient(new ClientPolicy(), parseHosts(props.idempotency().aerospike().hosts()));
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore aerospikeIdempotencyStore(IAerospikeClient client, BrodProperties props) {
        BrodProperties.Idempotency.Aerospike cfg = props.idempotency().aerospike();
        return new AerospikeIdempotencyStore(client, cfg.namespace(), cfg.set(), cfg.ttlSeconds());
    }

    private static Host[] parseHosts(String hosts) {
        return Arrays.stream(hosts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(BrodAerospikeAutoConfiguration::toHost)
                .toArray(Host[]::new);
    }

    private static Host toHost(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            return new Host(hostPort, 3000);
        }
        return new Host(hostPort.substring(0, colon).trim(),
                Integer.parseInt(hostPort.substring(colon + 1).trim()));
    }
}
