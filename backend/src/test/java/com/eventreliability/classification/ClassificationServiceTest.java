package com.eventreliability.classification;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of the rule-first / LLM-fallback composition (§11) — no Ollama needed; the LLM is a
 * test double. Proves the LLM only augments ambiguous results and never breaks the pipeline.
 */
class ClassificationServiceTest {

    private static ClassificationProperties props(boolean enabled, String mode) {
        return new ClassificationProperties(
                List.of(new ClassificationProperties.Rule(
                        "timeout", ".*Timeout.*", null,
                        FailureClassification.TRANSIENT, RecommendedAction.AUTO_RETRY, "timeout")),
                new ClassificationProperties.Llm(enabled, "http://localhost:11434", "test-model",
                        Duration.ofSeconds(5), mode));
    }

    private static ClassificationService service(ClassificationProperties p, LlmClassifier llm) {
        return new ClassificationService(new Classifier(p), llm, p);
    }

    /** Records calls and returns a fixed verdict; can be set to fail the test if invoked. */
    static final class FakeLlm implements LlmClassifier {
        final Optional<ClassificationResult> response;
        boolean failIfCalled = false;
        int calls = 0;

        FakeLlm(Optional<ClassificationResult> response) {
            this.response = response;
        }

        @Override
        public Optional<ClassificationResult> classify(String exceptionClass, String exceptionMessage) {
            calls++;
            if (failIfCalled) {
                throw new AssertionError("LLM must not be consulted here");
            }
            return response;
        }
    }

    @Test
    void llmDisabledLeavesUnknownAsUnknown() {
        FakeLlm llm = new FakeLlm(Optional.of(verdict(FailureClassification.BUSINESS)));
        ClassificationService service = service(props(false, "unknown-only"), llm);

        assertThat(service.classify("com.bank.MysteryException", "x").classification())
                .isEqualTo(FailureClassification.UNKNOWN);
        assertThat(llm.calls).isZero();
    }

    @Test
    void llmRescuesUnknownInUnknownOnlyMode() {
        FakeLlm llm = new FakeLlm(Optional.of(verdict(FailureClassification.BUSINESS)));
        ClassificationService service = service(props(true, "unknown-only"), llm);

        ClassificationResult result = service.classify("com.bank.MysteryException", "account frozen");
        assertThat(result.classification()).isEqualTo(FailureClassification.BUSINESS);
        assertThat(result.action()).isEqualTo(RecommendedAction.ROUTE_TO_OWNER);
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    void clearRuleResultSkipsLlmInUnknownOnlyMode() {
        FakeLlm llm = new FakeLlm(Optional.empty());
        llm.failIfCalled = true;
        ClassificationService service = service(props(true, "unknown-only"), llm);

        assertThat(service.classify("java.net.SocketTimeoutException", null).classification())
                .isEqualTo(FailureClassification.TRANSIENT);
    }

    @Test
    void alwaysModeLetsLlmOverrideAClearRuleResult() {
        FakeLlm llm = new FakeLlm(Optional.of(verdict(FailureClassification.POISON)));
        ClassificationService service = service(props(true, "always"), llm);

        assertThat(service.classify("java.net.SocketTimeoutException", null).classification())
                .isEqualTo(FailureClassification.POISON);
    }

    @Test
    void unavailableLlmFallsBackToRuleResult() {
        FakeLlm llm = new FakeLlm(Optional.empty()); // simulates Ollama down / low confidence
        ClassificationService service = service(props(true, "unknown-only"), llm);

        assertThat(service.classify("com.bank.MysteryException", "x").classification())
                .isEqualTo(FailureClassification.UNKNOWN);
        assertThat(llm.calls).isEqualTo(1);
    }

    private static ClassificationResult verdict(FailureClassification c) {
        return new ClassificationResult(c, RecommendedAction.defaultFor(c), "llm reason", "llm:test");
    }
}
