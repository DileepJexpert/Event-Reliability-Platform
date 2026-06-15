package com.eventreliability.api;

import java.util.List;

import com.eventreliability.api.dto.OwnershipDto;
import com.eventreliability.ownership.OwnershipService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ownership endpoint (§15): the topic/app → team mapping the console shows so each team can see what
 * it owns. Read-only; notification channels are not exposed.
 */
@RestController
@RequestMapping("/api/ownership")
public class OwnershipController {

    private final OwnershipService ownership;

    public OwnershipController(OwnershipService ownership) {
        this.ownership = ownership;
    }

    @GetMapping
    public OwnershipDto ownership() {
        List<OwnershipDto.TeamRule> rules = ownership.rules().stream()
                .map(r -> new OwnershipDto.TeamRule(r.sourceApp(), r.topic(), r.topicPrefix(), r.team()))
                .toList();
        return new OwnershipDto(ownership.defaultTeam(), rules);
    }
}
