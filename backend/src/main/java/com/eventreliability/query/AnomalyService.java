package com.eventreliability.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eventreliability.api.dto.AnomalyDto;
import com.eventreliability.api.dto.AnomalyItem;
import com.eventreliability.config.ReliabilityProperties;
import com.eventreliability.domain.FailureRecord;
import com.eventreliability.streams.ReadModels;

import org.springframework.stereotype.Service;

/**
 * Adaptive anomaly detection (§16, Dynatrace-style baselining). Instead of one fixed incident
 * threshold, each series (root-cause signature, source topic) is compared against ITS OWN recent
 * baseline: the count in the latest bucket is flagged when it exceeds {@code mean + sensitivity·stddev}
 * of the preceding buckets (and a minimum absolute count). A topic that normally sees 100/min isn't
 * flagged at 120; one that normally sees 1/min is flagged at 20 — and a brand-new signature appearing
 * in volume is caught as novelty. Read-only: computed on demand from the read models, no extra state.
 */
@Service
public class AnomalyService {

    private static final int MAX_ANOMALIES = 50;

    private final ReadModels readModels;
    private final ReliabilityProperties.Anomaly cfg;

    public AnomalyService(ReadModels readModels, ReliabilityProperties props) {
        this.readModels = readModels;
        this.cfg = props.anomaly();
    }

    public AnomalyDto detect() {
        long now = System.currentTimeMillis();
        long bucketMs = Math.max(1, cfg.bucket().toMillis());
        long lookbackMs = Math.max(bucketMs * 2, cfg.lookback().toMillis());
        long recentStart = now - bucketMs;
        long baselineStart = now - lookbackMs;

        if (!cfg.enabled()) {
            return new AnomalyDto(List.of(), bucketMs, lookbackMs, cfg.sensitivity(), now);
        }

        Map<String, Series> bySignature = new HashMap<>();
        Map<String, Series> byTopic = new HashMap<>();

        for (FailureRecord r : readModels.allFailures()) {
            Long ts = r.firstFailedAt();
            if (ts == null || ts < baselineStart || ts > now) {
                continue;
            }
            accumulate(bySignature, label(r.rootCauseSignature()), r, ts, recentStart, bucketMs);
            accumulate(byTopic, label(r.originalTopic()), r, ts, recentStart, bucketMs);
        }

        List<AnomalyItem> anomalies = new ArrayList<>();
        collect(anomalies, "ROOT_CAUSE", bySignature, baselineStart, recentStart, bucketMs);
        collect(anomalies, "TOPIC", byTopic, baselineStart, recentStart, bucketMs);
        anomalies.sort(Comparator.comparingDouble(AnomalyItem::score).reversed());
        if (anomalies.size() > MAX_ANOMALIES) {
            anomalies = anomalies.subList(0, MAX_ANOMALIES);
        }
        return new AnomalyDto(List.copyOf(anomalies), bucketMs, lookbackMs, cfg.sensitivity(), now);
    }

    private void accumulate(Map<String, Series> series, String key, FailureRecord r, long ts,
                            long recentStart, long bucketMs) {
        Series s = series.computeIfAbsent(key, k -> new Series());
        if (ts >= recentStart) {
            s.recentCount++;
            if (s.sample == null) {
                s.sample = r.correlationId();
            }
        } else {
            s.hist.merge(ts / bucketMs, 1L, Long::sum);
        }
    }

    private void collect(List<AnomalyItem> out, String dimension, Map<String, Series> series,
                         long baselineStart, long recentStart, long bucketMs) {
        long firstBucket = baselineStart / bucketMs;
        long lastBucket = (recentStart - 1) / bucketMs;
        int n = (int) Math.max(1, lastBucket - firstBucket + 1);

        for (Map.Entry<String, Series> e : series.entrySet()) {
            Series s = e.getValue();
            if (s.recentCount < cfg.minCount()) {
                continue;
            }
            double mean = 0;
            for (long b = firstBucket; b <= lastBucket; b++) {
                mean += s.hist.getOrDefault(b, 0L);
            }
            mean /= n;
            double var = 0;
            for (long b = firstBucket; b <= lastBucket; b++) {
                double c = s.hist.getOrDefault(b, 0L);
                var += (c - mean) * (c - mean);
            }
            double std = Math.sqrt(var / n);
            double expected = mean + cfg.sensitivity() * std;
            if (s.recentCount > expected) {
                double denom = std > 1e-9 ? std : Math.max(1.0, mean);
                double score = (s.recentCount - mean) / denom;
                out.add(new AnomalyItem(dimension, e.getKey(), s.recentCount,
                        round(mean), round(expected), round(score), s.sample));
            }
        }
    }

    private static String label(String s) {
        return (s == null || s.isBlank()) ? "(unknown)" : s;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Per-series tallies: the latest-bucket count and per-bucket history of the baseline window. */
    private static final class Series {
        private long recentCount;
        private String sample;
        private final Map<Long, Long> hist = new HashMap<>();
    }
}
