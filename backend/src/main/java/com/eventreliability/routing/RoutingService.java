package com.eventreliability.routing;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.retry.RetryScheduler;

import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Service;

/**
 * Routes a classified failure into one of the four treatment lanes (§7.1 step 3):
 * <ul>
 *   <li>{@code TRANSIENT} / {@code INFRASTRUCTURE} → tiered automatic retry (§10);</li>
 *   <li>{@code BUSINESS} → routed to its owner with a reason, never retried;</li>
 *   <li>{@code POISON} → quarantined immediately;</li>
 *   <li>{@code UNKNOWN} → parked for human review.</li>
 * </ul>
 */
@Service
public class RoutingService {

    private final RetryScheduler retryScheduler;
    private final ParkingService parkingService;

    public RoutingService(RetryScheduler retryScheduler, ParkingService parkingService) {
        this.retryScheduler = retryScheduler;
        this.parkingService = parkingService;
    }

    public void route(FailureRecord record, byte[] payload, Headers original) {
        FailureClassification classification =
                record.classification() == null ? FailureClassification.UNKNOWN : record.classification();
        switch (classification) {
            case TRANSIENT, INFRASTRUCTURE -> retryScheduler.schedule(record, payload, original);
            case BUSINESS -> parkingService.routeBusiness(record, payload, original);
            case POISON -> parkingService.quarantinePoison(record, payload, original);
            case UNKNOWN -> parkingService.parkUnknown(record, payload, original);
        }
    }
}
