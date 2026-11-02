package com.award.log.controller;

import com.award.log.agent.team.*;
import com.award.log.agent.skill.SkillCatalogLoader;
import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/agent-teams")
public class AgentTeamController {
    public record Start(String requestId,String goal,String skillId,List<TeamProtocol.Probe> observations) {}
    public record Approval(int revision,String planHash,String confirmCode) {}
    public record Edit(int revision,TeamProtocol.Plan plan,String reason,boolean externalEffectsReviewed) {}
    public record EvidenceRetry(int revision,List<TeamProtocol.Probe> observations,String reason,boolean externalEffectsReviewed) {}
    private final AgentTeamService teams;
    private final RequestUserResolver users;
    private final SkillCatalogLoader skills;
    public AgentTeamController(AgentTeamService teams,RequestUserResolver users,SkillCatalogLoader skills){this.teams=teams;this.users=users;this.skills=skills;}
    private int user(HttpServletRequest request){Integer id=users.currentUserId(request);if(id==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Login required");return id;}
    private TeamRunStore.Run owner(HttpServletRequest request,String id){return teams.owned(id,user(request),users.isAdmin(request));}
    @GetMapping public Result<?> list(HttpServletRequest request){return Result.success(teams.list(user(request),users.isAdmin(request)));}
    @GetMapping("/skills") public Result<?> skills(HttpServletRequest request){user(request);return Result.success(skills.all().stream().map(SkillCatalogLoader.YamlSkillDefinition::toView).toList());}
    @PostMapping public Result<?> start(HttpServletRequest request,@RequestBody Start body){
        return Result.success(teams.view(teams.start(user(request),body.requestId(),body.goal(),body.skillId(),body.observations())));
    }
    @GetMapping("/{id}") public Result<?> get(HttpServletRequest request,@PathVariable String id){return Result.success(teams.view(owner(request,id)));}
    @PostMapping("/{id}/approve") public Result<?> approve(HttpServletRequest request,@PathVariable String id,@RequestBody Approval approval){
        owner(request,id);return Result.success(teams.view(teams.approve(id,approval.revision(),approval.planHash(),approval.confirmCode(),String.valueOf(user(request)))));
    }
    @PostMapping("/{id}/plan") public Result<?> edit(HttpServletRequest request,@PathVariable String id,@RequestBody Edit edit){
        owner(request,id);return Result.success(teams.view(teams.edit(id,edit.revision(),edit.plan(),edit.reason(),String.valueOf(user(request)),edit.externalEffectsReviewed())));
    }
    @PostMapping("/{id}/cancel") public Result<?> cancel(HttpServletRequest request,@PathVariable String id){owner(request,id);return Result.success(teams.view(teams.cancel(id,String.valueOf(user(request)))));}
    @PostMapping("/{id}/evidence") public Result<?> retryEvidence(HttpServletRequest request,@PathVariable String id,@RequestBody EvidenceRetry body){
        owner(request,id);return Result.success(teams.view(teams.retryEvidence(id,body.revision(),body.observations(),body.reason(),String.valueOf(user(request)),body.externalEffectsReviewed())));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> invalid(){return Result.error(400,"团队请求或角色契约不完整，请核对工具、参数与证据引用");}
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> conflict(){return Result.error(409,"任务状态或计划已改变，批准已过期，或运行预算不足；请刷新后重新预览");}
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> missing(){return Result.error(404,"任务不存在或无权访问");}
}
