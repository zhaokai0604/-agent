package com.award.log.controller;

import com.award.log.agent.skill.SkillRoutingAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ops/skills")
@RequiredArgsConstructor
public class OpsYamlSkillController {

    private final SkillRoutingAdvisor skillRoutingAdvisor;

    @GetMapping("/yaml")
    public Map<String, Object> listYamlSkills() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("skills", skillRoutingAdvisor.catalogViews());
        return body;
    }
}
