package com.award.log.agent.skill;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath:skills/*.yaml 加载结构化运维 Skill。
 */
@Component
public class SkillCatalogLoader {

    private final ConcurrentHashMap<String, YamlSkillDefinition> skills = new ConcurrentHashMap<>();

    public SkillCatalogLoader() {
        reload();
    }

    public void reload() {
        skills.clear();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*.yaml");
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    Object loaded = yaml.load(in);
                    if (loaded instanceof Map<?, ?> map) {
                        YamlSkillDefinition def = mapToDefinition(map);
                        if (def.id() != null && !def.id().isBlank()) {
                            skills.put(def.id(), def);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 开发机无 skills 目录时不阻断启动
        }
    }

    public List<YamlSkillDefinition> all() {
        return new ArrayList<>(skills.values());
    }

    public YamlSkillDefinition find(String id) {
        return skills.get(id);
    }

    public YamlSkillDefinition findByPlaybook(String playbook) {
        if (playbook == null) {
            return null;
        }
        String key = playbook.toLowerCase(Locale.ROOT).replace('_', '-');
        for (YamlSkillDefinition def : skills.values()) {
            if (def.playbook() != null && def.playbook().equalsIgnoreCase(key)) {
                return def;
            }
            if (def.id() != null && def.id().equalsIgnoreCase(key)) {
                return def;
            }
        }
        return null;
    }

    private static YamlSkillDefinition mapToDefinition(Map<?, ?> map) {
        String id = str(map.get("id"));
        String description = str(map.get("description"));
        String playbook = str(map.get("playbook"));
        List<String> tools = listOf(map.get("tools"));
        List<String> stopConditions = listOf(map.get("stop_conditions"));
        List<String> redLines = listOf(map.get("red_lines"));
        int minSignals = 2;
        Object rules = map.get("decision_rules");
        if (rules instanceof Map<?, ?> rulesMap) {
            Object gate = rulesMap.get("confidence_gate");
            if (gate instanceof Map<?, ?> gateMap) {
                Object min = gateMap.get("min_independent_signals");
                if (min instanceof Number n) {
                    minSignals = n.intValue();
                }
            }
        }
        return new YamlSkillDefinition(id, description, playbook, tools, stopConditions, redLines, minSignals);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static List<String> listOf(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return Collections.emptyList();
    }

    public record YamlSkillDefinition(
            String id,
            String description,
            String playbook,
            List<String> tools,
            List<String> stopConditions,
            List<String> redLines,
            int minIndependentSignals) {

        public Map<String, Object> toView() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("description", description);
            m.put("playbook", playbook);
            m.put("tools", tools);
            m.put("stopConditions", stopConditions);
            m.put("redLines", redLines);
            m.put("minIndependentSignals", minIndependentSignals);
            return m;
        }
    }
}
