package com.eventreliability.api;

import com.eventreliability.api.dto.TrendsDto;
import com.eventreliability.query.TrendsService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trends / analytics endpoint (§15, §16). Read-only ({@code VIEWER} or {@code OPERATOR}); returns the
 * aggregated view the console's Trends tab renders.
 */
@RestController
@RequestMapping("/api/trends")
public class TrendsController {

    private final TrendsService trendsService;

    public TrendsController(TrendsService trendsService) {
        this.trendsService = trendsService;
    }

    @GetMapping
    public TrendsDto trends() {
        return trendsService.compute();
    }
}
