package com.award.log.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断置信门控：≥2 类独立信号一致才定性；否则返回最可能原因与缺失证据。
 */
@Component
public class DiagnosisConfidenceGate {

    public DiagnosisVerdict evaluate(List<Signal> signals, int minIndependentSignals) {
        int min = Math.max(2, minIndependentSignals);
        Map<String, List<Signal>> byCategory = new LinkedHashMap<>();
        for (Signal s : signals) {
            if (s == null || !s.present()) {
                continue;
            }
            byCategory.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
        }
        List<String> aligned = new ArrayList<>();
        String dominant = null;
        for (Map.Entry<String, List<Signal>> e : byCategory.entrySet()) {
            if (e.getValue().size() >= 1) {
                aligned.add(e.getKey() + ":" + e.getValue().get(0).detail());
            }
            if (dominant == null && !e.getValue().isEmpty()) {
                dominant = e.getValue().get(0).detail();
            }
        }
        boolean confirmed = byCategory.size() >= min;
        List<String> missing = new ArrayList<>();
        if (!byCategory.containsKey("cpu")) {
            missing.add("CPU/负载指标");
        }
        if (!byCategory.containsKey("io")) {
            missing.add("磁盘 I/O 指标");
        }
        if (!byCategory.containsKey("process")) {
            missing.add("进程/top 证据");
        }
        if (!byCategory.containsKey("journal")) {
            missing.add("journal/日志交叉印证");
        }
        return new DiagnosisVerdict(confirmed, dominant, aligned, missing);
    }

    public record Signal(String category, String detail, boolean present) {
    }

    public record DiagnosisVerdict(
            boolean confirmed,
            String likelyRootCause,
            List<String> alignedSignals,
            List<String> missingEvidence) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("confirmed", confirmed);
            m.put("likelyRootCause", likelyRootCause == null ? "" : likelyRootCause);
            m.put("alignedSignals", alignedSignals);
            m.put("missingEvidence", missingEvidence);
            m.put("confidenceLabel", confirmed ? "确定根因" : "最可能原因（证据不足）");
            return m;
        }
    }

    public static Signal cpuSignal(String detail, boolean high) {
        return new Signal("cpu", detail, high);
    }

    public static Signal ioSignal(String detail, boolean high) {
        return new Signal("io", detail, high);
    }

    public static Signal processSignal(String detail, boolean match) {
        return new Signal("process", detail, match);
    }

    public static Signal journalSignal(String detail, boolean match) {
        return new Signal("journal", detail, match);
    }
}
