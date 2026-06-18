package com.eventreliability.api;

import com.eventreliability.api.dto.ExposureDto;
import com.eventreliability.query.ExposureService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Financial exposure endpoint (§16). Read-only ({@code VIEWER}/{@code OPERATOR}): returns the
 * "value at risk" — the total business amount tied up in stuck failures, by currency / team / topic.
 * Aggregated figures only; no raw payloads are exposed.
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    @GetMapping
    public ExposureDto exposure() {
        return exposureService.compute();
    }
}
