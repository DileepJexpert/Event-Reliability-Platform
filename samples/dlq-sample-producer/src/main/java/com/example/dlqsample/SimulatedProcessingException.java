package com.example.dlqsample;

/**
 * Thrown by {@link SimulatedConsumer} to simulate an owning application failing to process a message.
 * After the configured retries are exhausted the record is routed to the platform DLQ, exactly as a
 * real consumer's terminal failure would be. Its class name is what the platform classifies on — with
 * no matching rule it lands as UNKNOWN and is parked for review (visible, and replayable).
 */
public class SimulatedProcessingException extends RuntimeException {

    public SimulatedProcessingException(String message) {
        super(message);
    }
}
