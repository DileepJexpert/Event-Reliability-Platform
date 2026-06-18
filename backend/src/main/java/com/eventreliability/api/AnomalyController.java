package com.eventreliability.api;

import com.eventreliability.api.dto.AnomalyDto;
import com.eventreliability.query.AnomalyService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptive anomaly detection endpoint (§16). Read-only ({@code VIEWER}/{@code OPERATOR}): returns the
 * root-cause / topic series whose latest-bucket failure rate is anomalous against their own baseline.
 */
@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {

    private final AnomalyService anomalyService;

    public AnomalyController(AnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @GetMapping
    public AnomalyDto anomalies() {
        return anomalyService.detect();
    }
}
