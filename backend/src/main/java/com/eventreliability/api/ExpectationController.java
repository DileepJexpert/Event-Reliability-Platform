package com.eventreliability.api;

import java.util.List;
import java.util.Map;

import com.eventreliability.api.dto.ExpectationReconciliationDto;
import com.eventreliability.api.dto.ExpectationRequest;
import com.eventreliability.audit.AuditService;
import com.eventreliability.domain.Expectation;
import com.eventreliability.query.ExpectationReconciliationService;
import com.eventreliability.query.ExpectationStore;
import com.eventreliability.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declared-expectation reconciliation (§16). A producer declares how many events it expects processed
 * for a source/batch; Brod reconciles that against the failures it captured and reports the shortfall.
 * Declaring is a mutating action (OPERATOR) and is audited; listing the reconciliation is read-only.
 */
@RestController
@RequestMapping("/api/reconciliation/expectations")
public class ExpectationController {

    private final ExpectationStore store;
    private final ExpectationReconciliationService reconciliation;
    private final AuditService auditService;

    public ExpectationController(ExpectationStore store, ExpectationReconciliationService reconciliation,
                                 AuditService auditService) {
        this.store = store;
        this.reconciliation = reconciliation;
        this.auditService = auditService;
    }

    /** {@code GET /api/reconciliation/expectations} — every declared expectation, reconciled. */
    @GetMapping
    public List<ExpectationReconciliationDto> list() {
        return reconciliation.reconcileAll();
    }

    /** {@code POST /api/reconciliation/expectations} — declare an expectation, returns its reconciliation. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExpectationReconciliationDto declare(@RequestBody(required = false) ExpectationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String key = trimToNull(request.key());
        String source = trimToNull(request.source());
        if (key == null) {
            throw new IllegalArgumentException("key is required (a unique id for this expectation/batch)");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required (the topic the events belong to)");
        }
        if (request.expectedCount() <= 0) {
            throw new IllegalArgumentException("expectedCount must be greater than 0");
        }
        String actor = CurrentUser.name();
        Expectation expectation = new Expectation(key, source, request.expectedCount(),
                trimToNull(request.label()), request.windowStartMs(), request.windowEndMs(),
                System.currentTimeMillis(), actor);
        store.declare(expectation);
        auditService.record(key, null, null, "EXPECTATION_DECLARED", actor,
                "expected " + request.expectedCount() + " event(s) on " + source,
                Map.of("source", source, "expectedCount", Long.toString(request.expectedCount())));
        return reconciliation.reconcile(expectation);
    }

    /** {@code DELETE /api/reconciliation/expectations/{key}} — forget an expectation. */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@PathVariable("key") String key) {
        store.delete(key);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
