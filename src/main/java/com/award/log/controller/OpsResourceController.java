package com.award.log.controller;

import com.award.log.agent.skill.SkillCatalogLoader;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只读事实层 Resources：os:// 风格 URI 快照。
 */
@RestController
@RequestMapping("/api/ops/resources")
@RequiredArgsConstructor
public class OpsResourceController {

    private static final long CACHE_TTL_MS = 30_000L;

    private final McpToolDispatcher mcpToolDispatcher;
    private final SkillCatalogLoader skillCatalogLoader;
    private final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    @GetMapping
    public Map<String, Object> listResources() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("resources", List.of(
                "os://disk/usage",
                "os://process/list",
                "os://audit/recent",
                "os://load/snapshot",
                "os://skills/catalog"));
        return body;
    }

    @GetMapping("/{namespace}/{name}")
    public Map<String, Object> readResource(@PathVariable String namespace, @PathVariable String name) {
        String uri = namespace + "/" + name;
        CachedSnapshot snap = cache.get(uri);
        long now = System.currentTimeMillis();
        if (snap != null && now - snap.atMs < CACHE_TTL_MS) {
            return snap.body;
        }
        Map<String, Object> body = resolve(uri);
        cache.put(uri, new CachedSnapshot(body, now));
        return body;
    }

    @GetMapping("/skills")
    public Map<String, Object> skillsCatalog() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (SkillCatalogLoader.YamlSkillDefinition def : skillCatalogLoader.all()) {
            views.add(def.toView());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("skills", views);
        return body;
    }

    private Map<String, Object> resolve(String uri) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", "os://" + uri);
        body.put("success", true);
        try {
            switch (uri) {
                case "disk/usage" -> body.put("data", dispatchTool("DiskTool", Map.of()));
                case "process/list" -> body.put("data", dispatchTool("ProcessTool", Map.of("operation", "top")));
                case "load/snapshot" -> body.put("data", dispatchTool("SystemLoadTool", Map.of()));
                case "audit/recent" -> body.put("data", Map.of("hint", "see /api/ops-trace/recent"));
                case "skills/catalog" -> {
                    body.put("data", skillCatalogLoader.all().stream()
                            .map(SkillCatalogLoader.YamlSkillDefinition::toView).toList());
                }
                default -> {
                    body.put("success", false);
                    body.put("error", "unknown resource: os://" + uri);
                }
            }
        } catch (Exception e) {
            body.put("success", false);
            body.put("error", e.getMessage());
        }
        return body;
    }

    private Object dispatchTool(String toolName, Map<String, Object> params) {
        var result = mcpToolDispatcher.dispatch(toolName, params);
        return result.success() ? result.data() : result.errorMessage();
    }

    private record CachedSnapshot(Map<String, Object> body, long atMs) {
    }
}
