package com.eventreliability;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.eventreliability.api.dto.AssistantAnswerDto;
import com.eventreliability.assistant.AssistantModel;
import com.eventreliability.assistant.AssistantService;
import com.eventreliability.audit.AuditService;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.domain.Incident;
import com.eventreliability.domain.MessageState;
import com.eventreliability.security.PayloadProtectionService;
import com.eventreliability.streams.ReadModels;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    @Test
    void answersFromContextMasksPiiAndCites() {
        ReadModels readModels = mock(ReadModels.class);
        FailureRecord failure = FailureRecord.builder()
                .correlationId("corr-1")
                .state(MessageState.PARKED_UNKNOWN)
                .classification(FailureClassification.UNKNOWN)
                .exceptionClass("com.bank.ValidationException")
                .exceptionMessage("account 123-45-6789 declined for jane@bank.com")
                .originalTopic("payments.events")
                .sourceApp("pay-svc")
                .firstFailedAt(System.currentTimeMillis() - 120_000)
                .updatedAt(System.currentTimeMillis())
                .build();
        when(readModels.allFailures()).thenReturn(List.of(failure));
        when(readModels.allIncidents()).thenReturn(List.of(new Incident(
                "inc-1", "com.bank.ValidationException|payments.events", "payments.events",
                7, 5, 0, 0, System.currentTimeMillis(), Incident.ACTIVE, "corr-1")));

        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AssistantModel model = new AssistantModel() {
            @Override public boolean available() {
                return true;
            }
            @Override public String chat(String systemPrompt, String userPrompt) {
                capturedPrompt.set(userPrompt);
                return "Most failures are validation errors on payments.events [corr-1] [inc-1].";
            }
        };
        AuditService audit = mock(AuditService.class);

        AssistantService service = new AssistantService(readModels, model, maskingService(), audit);
        AssistantAnswerDto answer = service.ask("why are payments failing?", "alice");

        // The model received context that includes the ids but NOT raw PII.
        assertThat(capturedPrompt.get()).contains("corr-1").contains("inc-1");
        assertThat(capturedPrompt.get()).contains("[MASKED:ssn]").contains("[MASKED:email]");
        assertThat(capturedPrompt.get()).doesNotContain("123-45-6789").doesNotContain("jane@bank.com");

        // Grounded, cited answer + audited query.
        assertThat(answer.grounded()).isTrue();
        assertThat(answer.answer()).contains("validation");
        assertThat(answer.citations()).contains("corr-1", "inc-1");
        assertThat(answer.contextSize()).isEqualTo(2);
        verify(audit).record(anyString(), any(), any(), eq("ASSISTANT_QUERY"), eq("alice"), anyString(), any());
    }

    @Test
    void returnsDisabledMessageWhenModelUnavailable() {
        ReadModels readModels = mock(ReadModels.class);
        AssistantModel model = new AssistantModel() {
            @Override public boolean available() {
                return false;
            }
            @Override public String chat(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("should not be called");
            }
        };

        AssistantService service = new AssistantService(
                readModels, model, maskingService(), mock(AuditService.class));
        AssistantAnswerDto answer = service.ask("anything", "alice");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.answer()).containsIgnoringCase("not configured");
        assertThat(answer.contextSize()).isZero();
    }

    private static PayloadProtectionService maskingService() {
        ReliabilityProperties props = new ReliabilityProperties(
                "reliability.", null, null, null, null, null, null, null, null, null, null,
                new ReliabilityProperties.PayloadProtection(false, null, true, List.of()), null, null);
        return new PayloadProtectionService(props);
    }
}
