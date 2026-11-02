package com.award.log.controller;

import com.award.log.agent.skill.SkillRoutingAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import com.award.log.security.RequestUserResolver;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ops/skills")
@RequiredArgsConstructor
public class OpsYamlSkillController {

    private final SkillRoutingAdvisor skillRoutingAdvisor;
    private final RequestUserResolver requestUserResolver;

    @GetMapping("/yaml")
    public Map<String, Object> listYamlSkills(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.putAll(skillRoutingAdvisor.catalogSnapshot());
        body.put("canManage",requestUserResolver.isAdmin(request)&&requestUserResolver.currentUserId(request)!=null);
        return body;
    }

    public record PublishRequest(String expectedFingerprint,String candidateFingerprint,String reason) {}
    public record RollbackRequest(String expectedFingerprint,String targetFingerprint,String reason) {}

    @PostMapping("/releases/preview")
    public Map<String,Object> preview(HttpServletRequest request) {
        requireAdmin(request);return Map.of("success",true,"preview",skillRoutingAdvisor.previewRelease());
    }
    @PostMapping("/releases/publish")
    public Map<String,Object> publish(HttpServletRequest request,@RequestBody PublishRequest body) {
        String actor=requireAdmin(request);
        skillRoutingAdvisor.publish(body.expectedFingerprint(),body.candidateFingerprint(),actor,body.reason());
        return listYamlSkills(request);
    }
    @PostMapping("/releases/rollback")
    public Map<String,Object> rollback(HttpServletRequest request,@RequestBody RollbackRequest body) {
        String actor=requireAdmin(request);
        skillRoutingAdvisor.rollback(body.expectedFingerprint(),body.targetFingerprint(),actor,body.reason());
        return listYamlSkills(request);
    }
    @GetMapping("/releases/history")
    public Map<String,Object> history(HttpServletRequest request,@RequestParam(defaultValue="20") int limit) {
        requireAdmin(request);return Map.of("success",true,"history",skillRoutingAdvisor.releaseHistory(limit));
    }
    private String requireAdmin(HttpServletRequest request) {
        Integer id=requestUserResolver.currentUserId(request);
        if(!requestUserResolver.isAdmin(request)||id==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"管理员权限不足");
        return id.toString();
    }
    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class})
    public org.springframework.http.ResponseEntity<Map<String,Object>> releaseFailure(RuntimeException e) {
        var status=e instanceof IllegalArgumentException?org.springframework.http.HttpStatus.BAD_REQUEST:org.springframework.http.HttpStatus.CONFLICT;
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("success",false,"message",e.getMessage()==null?"Skill 发布请求失败":e.getMessage()));
    }
}
