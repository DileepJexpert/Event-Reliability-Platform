package com.eventreliability.control;

import com.eventreliability.common.JsonCodec;
import com.eventreliability.domain.ControlCommand;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Executes control-plane commands (§13). Consuming the commands from Kafka (rather than acting inline
 * in the REST call) means exactly one instance — the partition owner — performs each replay, so
 * mutations are HA, ordered, and replay-safe across the N-instance consumer group.
 */
@Component
public class ControlCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ControlCommandListener.class);

    private final JsonCodec json;
    private final ReplayService replayService;

    public ControlCommandListener(JsonCodec json, ReplayService replayService) {
        this.json = json;
        this.replayService = replayService;
    }

    @KafkaListener(topics = "#{@topicNames.controlCommands()}", id = "control")
    public void onCommand(ConsumerRecord<String, byte[]> record) {
        log.info("RECV <- topic={} key={} partition={} offset={}", record.topic(), record.key(),
                record.partition(), record.offset());
        ControlCommand command = json.fromBytes(record.value(), ControlCommand.class);
        if (command == null || command.type() == null) {
            log.warn("Ignoring malformed control command on key {}", record.key());
            return;
        }
        log.info("Control command {} corr={} incident={} actor={}",
                command.type(), command.correlationId(), command.incidentId(), command.actor());
        switch (command.type()) {
            case REPLAY -> replayService.replaySingle(command.correlationId(), command.actor(), command.reason());
            case BULK_REPLAY -> replayService.bulkReplay(command.incidentId(), command.actor(), command.reason());
            case QUARANTINE -> replayService.quarantine(command.correlationId(), command.actor(), command.reason());
        }
    }
}
