package com.award.log.agent.skill;

import com.award.log.agent.OpsIntentRouter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 YAML Skill 目录映射到 Playbook 与工具计划。
 */
@Component
public class SkillRoutingAdvisor {

    private final SkillCatalogLoader skillCatalogLoader;

    public SkillRoutingAdvisor(SkillCatalogLoader skillCatalogLoader) {
        this.skillCatalogLoader = skillCatalogLoader;
    }

    public YamlSkillPlan planForPlaybook(OpsIntentRouter.Playbook playbook) {
        if (playbook == null) {
            return YamlSkillPlan.empty();
        }
        String key = playbook.name().toLowerCase(Locale.ROOT).replace('_', '-');
        SkillCatalogLoader.YamlSkillDefinition def = skillCatalogLoader.findByPlaybook(key);
        if (def == null) {
            def = skillCatalogLoader.find(key);
        }
        if (def == null) {
            return YamlSkillPlan.empty();
        }
        return new YamlSkillPlan(
                def.id(),
                def.description(),
                def.tools(),
                def.minIndependentSignals(),
                def.redLines(),
                def.toView());
    }

    public List<Map<String, Object>> catalogViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillCatalogLoader.YamlSkillDefinition def : skillCatalogLoader.all()) {
            out.add(def.toView());
        }
        return out;
    }

    public record YamlSkillPlan(
            String id,
            String description,
            List<String> tools,
            int minIndependentSignals,
            List<String> redLines,
            Map<String, Object> view) {

        static YamlSkillPlan empty() {
            return new YamlSkillPlan("", "", List.of(), 2, List.of(), Map.of());
        }

        public boolean present() {
            return id != null && !id.isBlank();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("description", description);
            m.put("tools", tools);
            m.put("minIndependentSignals", minIndependentSignals);
            m.put("redLines", redLines);
            if (view != null && !view.isEmpty()) {
                m.putAll(view);
            }
            return m;
        }
    }
}
