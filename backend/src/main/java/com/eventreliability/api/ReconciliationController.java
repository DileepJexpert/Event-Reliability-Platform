package com.eventreliability.api;

import com.eventreliability.api.dto.ReconciliationDto;
import com.eventreliability.query.ReconciliationService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation / completeness endpoint (§16). Read-only ({@code VIEWER}/{@code OPERATOR}): of every
 * captured failure, how many have completed vs are still open, by source / topic, with the oldest
 * unreconciled items.
 */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public ReconciliationDto reconciliation() {
        return reconciliationService.compute();
    }
}
