package com.eventreliability.api;

import java.util.List;

import com.eventreliability.api.dto.ComplianceRow;
import com.eventreliability.query.ComplianceExportService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance export (§17): a regulator-ready register of failed transactions and their disposition
 * over a date range, as JSON or CSV. Read-only and metadata-only (no payloads). In a regulated
 * deployment this endpoint should be gated to a compliance/approver role.
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceExportService exportService;

    public ComplianceController(ComplianceExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * {@code GET /api/compliance/export?from=&to=&format=json|csv} — failures whose first-failed time
     * is in [from, to] (epoch millis, both optional). CSV downloads as an attachment.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "format", required = false, defaultValue = "json") String format) {
        List<ComplianceRow> rows = exportService.rows(from, to);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"compliance-export.csv\"")
                    .contentType(MediaType.valueOf("text/csv"))
                    .body(exportService.toCsv(rows));
        }
        return ResponseEntity.ok(rows);
    }
}
