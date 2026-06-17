package com.eventreliability.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.eventreliability.api.dto.AssistantAnswerDto;
import com.eventreliability.audit.AuditService;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.security.PayloadProtectionService;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * The operations assistant. Grounds a chat model in Brod's own read models (retrieval-augmented):
 * it pulls the active incidents and most recent failures, masks any PII out of that context, asks the
 * model the operator's question, and returns a cited answer. Every query is audited.
 *
 * <p>Strictly read-only — it answers, summarises and drafts; it never takes a control-plane action.
 */
@Service
public class AssistantService {

    private static final int MAX_CONTEXT_FAILURES = 40;
    private static final int MAX_CONTEXT_INCIDENTS = 20;
    private static final String AUDIT_KEY = "assistant";

    private static final String SYSTEM_PROMPT = """
            You are Brod's failure-operations assistant for a bank's Kafka reliability platform.
            Answer the user's question using ONLY the CONTEXT below (active incidents and recent failures).
            If the answer is not in the context, say you don't have that information — never guess.
            Be concise and factual. When you reference a specific failure or incident, cite its id in
            square brackets, e.g. [corr-123] or [inc-7]. Never invent ids, numbers, or root causes.""";

    private final ReadModels readModels;
    private final AssistantModel model;
    private final PayloadProtectionService payloadProtection;
    private final AuditService auditService;

    public AssistantService(ReadModels readModels, AssistantModel model,
                            PayloadProtectionService payloadProtection, AuditService auditService) {
        this.readModels = readModels;
        this.model = model;
        this.payloadProtection = payloadProtection;
        this.auditService = auditService;
    }

    public AssistantAnswerDto ask(String question, String actor) {
        if (!model.available()) {
            return new AssistantAnswerDto(
                    "The assistant is not configured. Set reliability.assistant.* to a self-hosted, "
                            + "OpenAI-compatible model endpoint to enable it.",
                    List.of(), 0, false);
        }

        List<Incident> incidents = readModels.allIncidents().stream()
                .filter(i -> Incident.ACTIVE.equals(i.status()))
                .sorted(Comparator.comparingLong(Incident::firstSeenAt).reversed())
                .limit(MAX_CONTEXT_INCIDENTS)
                .toList();
        List<FailureRecord> failures = readModels.allFailures().stream()
                .sorted(Comparator.comparingLong((FailureRecord r) ->
                        r.updatedAt() == null ? 0L : r.updatedAt()).reversed())
                .limit(MAX_CONTEXT_FAILURES)
                .toList();

        List<String> citations = new ArrayList<>();
        String context = buildContext(incidents, failures, citations);
        // Defence in depth: even with a self-hosted model, never put raw PII in the prompt.
        String maskedContext = payloadProtection.maskText(context);

        String userPrompt = "QUESTION:\n" + question + "\n\nCONTEXT:\n" + maskedContext;
        String answer = model.chat(SYSTEM_PROMPT, userPrompt);

        int contextSize = incidents.size() + failures.size();
        auditService.record(AUDIT_KEY, null, null, "ASSISTANT_QUERY", actor, truncate(question, 280),
                Map.of("contextSize", Integer.toString(contextSize),
                        "citations", Integer.toString(citations.size())));

        return new AssistantAnswerDto(answer, List.copyOf(citations), contextSize, true);
    }

    private String buildContext(List<Incident> incidents, List<FailureRecord> failures, List<String> citations) {
        StringBuilder sb = new StringBuilder();
        sb.append("ACTIVE INCIDENTS (").append(incidents.size()).append("):\n");
        if (incidents.isEmpty()) {
            sb.append("(none)\n");
        }
        for (Incident i : incidents) {
            citations.add(i.id());
            sb.append("- [").append(i.id()).append("] rootCause=").append(i.rootCause())
                    .append(" topic=").append(i.sourceTopic())
                    .append(" count=").append(i.count())
                    .append(" threshold=").append(i.threshold()).append('\n');
        }
        sb.append("\nRECENT FAILURES (").append(failures.size()).append("):\n");
        if (failures.isEmpty()) {
            sb.append("(none)\n");
        }
        long now = System.currentTimeMillis();
        for (FailureRecord r : failures) {
            citations.add(r.correlationId());
            sb.append("- [").append(r.correlationId()).append("] state=").append(r.state())
                    .append(" class=").append(r.classification())
                    .append(" exception=").append(r.exceptionClass())
                    .append(" msg=").append(nullSafe(r.exceptionMessage()))
                    .append(" topic=").append(r.originalTopic())
                    .append(" app=").append(r.sourceApp())
                    .append(" ageMin=").append(ageMinutes(r, now)).append('\n');
        }
        return sb.toString();
    }

    private static long ageMinutes(FailureRecord r, long now) {
        Long first = r.firstFailedAt();
        return first == null ? -1 : Math.max(0, (now - first) / 60000);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
