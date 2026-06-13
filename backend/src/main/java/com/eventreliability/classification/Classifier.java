package com.eventreliability.classification;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.eventreliability.domain.FailureClassification;
import com.eventreliability.domain.RecommendedAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rule-based classifier (§7.1 step 2, §11). Compiles the externalised rule table once at startup and
 * evaluates rules in order; the first whose patterns match decides the taxonomy class and action.
 * When nothing matches the failure is conservatively treated as {@link FailureClassification#UNKNOWN}
 * and parked for human review — never blindly retried.
 *
 * <p>Classification is intentionally a pure function of the header context here, so it can later be
 * swapped for (or augmented by) a heavier/LLM classifier behind the same async topic (§11) without
 * touching the rest of the pipeline.
 */
@Component
public class Classifier {

    private static final Logger log = LoggerFactory.getLogger(Classifier.class);

    private static final ClassificationResult DEFAULT = new ClassificationResult(
            FailureClassification.UNKNOWN, RecommendedAction.PARK_FOR_REVIEW,
            "No classification rule matched — parked for human review", "default");

    private final List<CompiledRule> rules;

    public Classifier(ClassificationProperties properties) {
        this.rules = new ArrayList<>();
        for (ClassificationProperties.Rule r : properties.rules()) {
            rules.add(new CompiledRule(
                    r.name(),
                    compile(r.exceptionPattern()),
                    compile(r.messagePattern()),
                    r.classification(),
                    r.action() != null ? r.action() : RecommendedAction.defaultFor(r.classification()),
                    r.reason()));
        }
        log.info("Loaded {} classification rule(s)", rules.size());
    }

    public ClassificationResult classify(String exceptionClass, String exceptionMessage) {
        String ex = exceptionClass == null ? "" : exceptionClass;
        String msg = exceptionMessage == null ? "" : exceptionMessage;
        for (CompiledRule rule : rules) {
            if (rule.exception() != null && !rule.exception().matcher(ex).matches()) {
                continue;
            }
            if (rule.message() != null && !rule.message().matcher(msg).matches()) {
                continue;
            }
            return new ClassificationResult(rule.classification(), rule.action(), rule.reason(), rule.name());
        }
        return DEFAULT;
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private record CompiledRule(
            String name,
            Pattern exception,
            Pattern message,
            FailureClassification classification,
            RecommendedAction action,
            String reason) {
    }
}
