package com.eventreliability.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Provisions every platform topic explicitly at startup via {@link AdminClient} (§8, §18.5).
 * Auto-create is assumed disabled on target/regulated clusters, so the platform must create its own
 * topics with the right cleanup policy:
 *
 * <ul>
 *   <li>{@code reliability.state} and {@code reliability.views.*} — <b>compacted</b> (latest per key).</li>
 *   <li>{@code reliability.audit} — <b>append-only</b> (compaction off) with long retention.</li>
 *   <li>everything else — standard delete-retention work/queue topics.</li>
 * </ul>
 *
 * <p>Implemented as a low-phase {@link SmartLifecycle} so topics exist <em>before</em> the listener
 * containers and the Kafka Streams topology start (both run at much higher phases). Idempotent:
 * only missing topics are created, so restarts are safe.
 */
@Component
public class TopicProvisioner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TopicProvisioner.class);
    private static final int ADMIN_TIMEOUT_SECONDS = 30;

    private final KafkaAdmin kafkaAdmin;
    private final TopicNames topics;
    private final ReliabilityProperties props;
    private volatile boolean running;

    public TopicProvisioner(KafkaAdmin kafkaAdmin, TopicNames topics, ReliabilityProperties props) {
        this.kafkaAdmin = kafkaAdmin;
        this.topics = topics;
        this.props = props;
    }

    @Override
    public void start() {
        List<NewTopic> desired = desiredTopics();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> existing = admin.listTopics().names().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<NewTopic> toCreate = desired.stream()
                    .filter(t -> !existing.contains(t.name()))
                    .collect(Collectors.toList());

            if (toCreate.isEmpty()) {
                log.info("All {} platform topics already present; nothing to provision.", desired.size());
            } else {
                admin.createTopics(toCreate).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Provisioned {} topic(s): {}", toCreate.size(),
                        toCreate.stream().map(NewTopic::name).collect(Collectors.joining(", ")));
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Topic provisioning failed — the platform cannot start without its topics", ex);
        }
        running = true;
    }

    private List<NewTopic> desiredTopics() {
        int partitions = props.topics().partitions();
        short rf = props.topics().replicationFactor();
        List<NewTopic> list = new ArrayList<>();

        // Work / queue topics (delete retention).
        list.add(plain(topics.inbound(), partitions, rf));
        list.add(plain(topics.classify(), partitions, rf));
        list.add(plain(topics.parked(), partitions, rf));
        list.add(plain(topics.businessRouted(), partitions, rf));
        list.add(plain(topics.controlCommands(), partitions, rf));

        // One topic per retry tier (§10).
        for (String tier : props.retry().tierNames()) {
            list.add(plain(topics.retry(tier), partitions, rf));
        }

        // System of record + materialised views: log compaction.
        list.add(compacted(topics.state(), partitions, rf));
        list.add(compacted(topics.viewsIncidents(), partitions, rf));
        list.add(compacted(topics.viewsAudit(), partitions, rf));
        // Maker-checker control requests: compacted by request id (the pending-approvals view).
        list.add(compacted(topics.controlRequests(), partitions, rf));

        // Append-only immutable audit log: compaction OFF, long retention.
        list.add(appendOnly(topics.audit(), partitions, rf, props.topics().auditRetentionMs()));

        // Incident feed: short-lived delete retention.
        list.add(retained(topics.incidents(), partitions, rf, 7L * 24 * 60 * 60 * 1000));

        return list;
    }

    private static NewTopic plain(String name, int partitions, short rf) {
        return new NewTopic(name, partitions, rf)
                .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE));
    }

    private static NewTopic compacted(String name, int partitions, short rf) {
        return new NewTopic(name, partitions, rf).configs(Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
                TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1",
                TopicConfig.SEGMENT_MS_CONFIG, "600000"));
    }

    private static NewTopic appendOnly(String name, int partitions, short rf, long retentionMs) {
        return new NewTopic(name, partitions, rf).configs(Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs)));
    }

    private static NewTopic retained(String name, int partitions, short rf, long retentionMs) {
        return new NewTopic(name, partitions, rf).configs(Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMs)));
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Start very early — before listener containers and the Kafka Streams topology.
        return Integer.MIN_VALUE + 1000;
    }
}
