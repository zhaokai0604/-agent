package com.award.log.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
@Qualifier("drainParserV2")
public class DrainParserV2 implements DrainParser {

    private final Node root;
    private final AtomicLong idCounter;
    private volatile int dynamicDepth;

    public DrainParserV2() {
        this.root = new Node("ROOT");
        this.idCounter = new AtomicLong(1);
        this.dynamicDepth = 4;
    }

    @Override
    public String parse(String raw) {
        return parse(raw, null);
    }

    @Override
    public String parse(String raw, Consumer<TemplateInfo> onNewTemplate) {
        if (raw == null || raw.isBlank()) {
            return "EMPTY";
        }

        String[] tokens = tokenize(raw);
        Node current = root;

        for (int i = 0; i < Math.min(dynamicDepth, tokens.length); i++) {
            String key = normalizeToken(tokens[i]);
            Node next = current.children.get(key);
            if (next == null) {
                return addNewTemplate(current, tokens, raw, onNewTemplate);
            }
            current = next;
        }

        return handleHit(current, tokens, raw, onNewTemplate);
    }

    private String handleHit(Node node, String[] tokens, String raw, Consumer<TemplateInfo> onNewTemplate) {
        List<TemplateLeaf> templates = node.getTemplates();
        if (templates.isEmpty()) {
            return addNewTemplate(node, tokens, raw, onNewTemplate);
        }

        TemplateLeaf leaf = templates.get(0);
        leaf.count.incrementAndGet();
        return leaf.templateId;
    }

    private String addNewTemplate(Node node, String[] tokens, String raw, Consumer<TemplateInfo> onNewTemplate) {
        dynamicDepth = Math.max(3, Math.min(10, Math.max(dynamicDepth, tokens.length / 5)));

        for (int i = 0; i < Math.min(dynamicDepth, tokens.length); i++) {
            String key = normalizeToken(tokens[i]);
            node.children.putIfAbsent(key, new Node(key));
            node = node.children.get(key);
        }

        String templateId = "TMP-" + idCounter.getAndIncrement();
        TemplateLeaf newLeaf = new TemplateLeaf(templateId, String.join(" ", tokens));
        node.addTemplate(newLeaf);

        if (onNewTemplate != null) {
            onNewTemplate.accept(newLeaf);
        }

        return templateId;
    }

    @Override
    public List<TemplateInfo> parallelParse(List<String> rawLogs, int threads) {
        if (rawLogs == null || rawLogs.isEmpty()) {
            return Collections.emptyList();
        }

        List<TemplateInfo> results = Collections.synchronizedList(new ArrayList<>());
        int partitionSize = Math.max(1, rawLogs.size() / threads);

        Thread[] threadList = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int start = t * partitionSize;
            int end = Math.min(start + partitionSize, rawLogs.size());
            if (start >= rawLogs.size()) break;

            threadList[t] = new Thread(() -> {
                for (int i = start; i < end; i++) {
                    parse(rawLogs.get(i), results::add);
                }
            }, "drain-v2-worker-" + t);
            threadList[t].start();
        }

        for (Thread thread : threadList) {
            if (thread != null) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return results;
    }

    @Override
    public int getTemplateCount() {
        return (int) idCounter.get() - 1;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("version", "v2");
        stats.put("totalTemplates", getTemplateCount());
        stats.put("dynamicDepth", dynamicDepth);
        stats.put("type", "ConcurrentHashMap + synchronizedList");
        return stats;
    }

    @Override
    public String getVersion() {
        return "DrainParserV2";
    }

    private String[] tokenize(String raw) {
        String line = raw == null ? "" : raw.replaceAll("\\d+", "*")
                                            .replaceAll("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", "*")
                                            .replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "*")
                                            .replaceAll("\\s+", " ")
                                            .trim();
        return line.isBlank() ? new String[]{"EMPTY"} : line.split(" ");
    }

    private String normalizeToken(String token) {
        if (token == null || token.isBlank()) return "*";
        if (token.matches(".*\\*.*")) return "*";
        return token.toLowerCase();
    }

    public static class Node {
        private final String key;
        private final Map<String, Node> children;
        private final List<TemplateLeaf> templates;

        public Node(String key) {
            this.key = key;
            this.children = new ConcurrentHashMap<>();
            this.templates = Collections.synchronizedList(new ArrayList<>());
        }

        public String getKey() { return key; }

        public List<TemplateLeaf> getTemplates() {
            synchronized (templates) {
                return new ArrayList<>(templates);
            }
        }

        public void addTemplate(TemplateLeaf leaf) {
            synchronized (templates) {
                templates.add(leaf);
            }
        }
    }

    public static class TemplateLeaf implements TemplateInfo {
        private final String templateId;
        private final String templateText;
        private final AtomicLong count;

        public TemplateLeaf(String templateId, String templateText) {
            this.templateId = templateId;
            this.templateText = templateText;
            this.count = new AtomicLong(1);
        }

        @Override
        public String getTemplateId() { return templateId; }

        @Override
        public String getTemplateText() { return templateText; }

        @Override
        public long getCount() { return count.get(); }

        public void incrementCount() { count.incrementAndGet(); }
    }
}
