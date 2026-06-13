package com.eventreliability.classification;

import java.util.List;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-unit coverage of the rule-based classifier — order, matching and the conservative default. */
class ClassifierTest {

    private final Classifier classifier = new Classifier(new ClassificationProperties(List.of(
            new ClassificationProperties.Rule("timeout", ".*Timeout.*", null,
                    FailureClassification.TRANSIENT, RecommendedAction.AUTO_RETRY, "timeout"),
            new ClassificationProperties.Rule("infra", ".*(IOException|SocketException).*", null,
                    FailureClassification.INFRASTRUCTURE, RecommendedAction.AUTO_RETRY, "infra"),
            new ClassificationProperties.Rule("poison", ".*SerializationException.*", null,
                    FailureClassification.POISON, RecommendedAction.QUARANTINE, "poison"),
            new ClassificationProperties.Rule("business", null, ".*account frozen.*",
                    FailureClassification.BUSINESS, RecommendedAction.ROUTE_TO_OWNER, "business")), null));

    @Test
    void firstMatchingRuleWins() {
        // SocketTimeoutException matches the timeout rule before the infra rule.
        ClassificationResult r = classifier.classify("java.net.SocketTimeoutException", null);
        assertThat(r.classification()).isEqualTo(FailureClassification.TRANSIENT);
        assertThat(r.matchedRule()).isEqualTo("timeout");
    }

    @Test
    void matchesOnExceptionClass() {
        assertThat(classifier.classify("java.io.IOException", "boom").classification())
                .isEqualTo(FailureClassification.INFRASTRUCTURE);
        assertThat(classifier.classify("o.a.k.common.errors.SerializationException", null).classification())
                .isEqualTo(FailureClassification.POISON);
    }

    @Test
    void matchesOnMessageWhenExceptionPatternAbsent() {
        ClassificationResult r = classifier.classify("com.bank.SomeException", "The account frozen by ops");
        assertThat(r.classification()).isEqualTo(FailureClassification.BUSINESS);
    }

    @Test
    void unmatchedFailureDefaultsToUnknownAndPark() {
        ClassificationResult r = classifier.classify("com.bank.MysteryException", "no idea");
        assertThat(r.classification()).isEqualTo(FailureClassification.UNKNOWN);
        assertThat(r.action()).isEqualTo(RecommendedAction.PARK_FOR_REVIEW);
    }

    @Test
    void nullExceptionClassDoesNotMatchSubstringRules() {
        assertThat(classifier.classify(null, null).classification())
                .isEqualTo(FailureClassification.UNKNOWN);
    }
}
