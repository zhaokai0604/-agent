package com.award.log.agent.team;

import static com.award.log.agent.team.TeamProtocol.*;
import com.award.log.agent.skill.SkillCatalogLoader.YamlSkillDefinition;
import com.award.log.governance.OpsExecutionPipeline;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.*;
import com.award.log.security.effect.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Model output cannot call a tool. Only this gateway invokes the existing governed pipeline. */
@Component
public class TeamToolGateway {
    public record Prepared(Plan plan,List<Map<String,Object>> previews,List<Map<String,Object>> tickets,long expiresAt) {}
    private final OpsExecutionPipeline pipeline;
    private final McpToolCatalog catalog;
    private final TeamDataPolicy data;
    private final ToolEffectResolver effects;
    public TeamToolGateway(OpsExecutionPipeline pipeline,McpToolCatalog catalog,TeamDataPolicy data,ToolEffectResolver effects){
        this.pipeline=pipeline;this.catalog=catalog;this.data=data;this.effects=effects;
    }
    public boolean writes(Probe probe){return ToolOperationPolicy.isWriteOperation(probe.tool(),probe.parameters());}
    private void probe(Probe probe,YamlSkillDefinition skill,boolean readOnly){
        if(probe==null||probe.tool()==null||!skill.tools().contains(probe.tool())||!catalog.isRegistered(probe.tool())||!catalog.isHttpAllowed(probe.tool()))
            throw new IllegalArgumentException("Tool is outside the pinned Skill contract");
        data.executable(probe.parameters());
        if(readOnly&&writes(probe))throw new IllegalArgumentException("Verification must be read-only");
    }
    public void validateProbes(List<Probe> probes,YamlSkillDefinition skill){
        if(probes==null||probes.isEmpty()||probes.size()>3)throw new IllegalArgumentException("One to three evidence probes required");
        probes.forEach(p->probe(p,skill,false));
    }
    public void validate(Plan plan,YamlSkillDefinition skill){
        if(plan==null||plan.summary()==null||plan.summary().isBlank()||plan.steps()==null||plan.steps().isEmpty()||plan.steps().size()>3
                ||plan.verification()==null||plan.verification().isEmpty()||plan.verification().size()>3
                ||plan.stopConditions()==null||plan.stopConditions().isEmpty()||plan.stopConditions().size()>8)
            throw new IllegalArgumentException("Incomplete plan contract");
        data.bounded(data.encode(plan),12000);
        // The persisted plan includes free-text summaries and stop conditions as well as tool parameters.
        data.executable(plan);
        for(Step step:plan.steps()){
            if(step.expectedOutcome()==null||step.expectedOutcome().isBlank())throw new IllegalArgumentException("Expected outcome is required");
            probe(step.probe(),skill,false);
        }
        plan.verification().forEach(p->probe(p,skill,true));
    }
    private <T>T context(TeamRunStore.Run run,java.util.function.Supplier<T> work){
        var previous=OpsSecurityContext.get();
        OpsSecurityContext.open(run.id(),run.goal(),false,McpToolSurface.FULL,false,false,false,String.valueOf(run.userId()),run.id());
        try{return work.get();}finally{OpsSecurityContext.restore(previous);}
    }
    private void success(Map<String,Object> body){if(!Boolean.TRUE.equals(body.get("success")))throw new IllegalStateException("Governed tool operation failed");}
    private Map<String,Object> realParams(Probe probe){
        var params=new LinkedHashMap<>(probe.parameters());
        params.remove("confirmDelete");params.remove("confirmRestart");params.remove("confirmStop");params.remove("confirmKill");
        if(writes(probe))params.put("dryRun",false);
        return params;
    }
    public Evidence observe(TeamRunStore.Run run,Probe probe,String id){
        return context(run,()->{
            var result=writes(probe)?pipeline.preview(probe.tool(),realParams(probe),run.goal(),String.valueOf(run.userId()))
                    :pipeline.executeDirect(probe.tool(),probe.parameters(),run.goal(),String.valueOf(run.userId()));
            success(result.body());
            String text=data.safe(Map.of("parameters",probe.parameters(),"result",result.body()));
            text=data.clip(text,6000);
            return new Evidence(id,probe.tool(),text);
        });
    }
    public Prepared prepare(TeamRunStore.Run run,Plan plan){
        List<Map<String,Object>> previews=new ArrayList<>(),tickets=new ArrayList<>();long[] expires={Long.MAX_VALUE};
        context(run,()->{
            for(Step step:plan.steps()){
                if(!writes(step.probe())){previews.add(Map.of("tool",step.tool(),"readOnly",true));tickets.add(Map.of());continue;}
                var params=realParams(step.probe());
                var preview=pipeline.preview(step.tool(),params,run.goal(),String.valueOf(run.userId()));success(preview.body());
                var proposal=pipeline.propose(step.tool(),params,run.goal(),String.valueOf(run.userId()),preview.dryRunPreviewJson(),run.id());
                if(!Boolean.TRUE.equals(proposal.body().get("needConfirm")))throw new IllegalStateException("No bound approval proposal");
                var ticket=new LinkedHashMap<String,Object>();
                for(String key:List.of("confirmationId","capabilityToken","confirmToken")){
                    Object value=proposal.body().get(key);if(value==null||String.valueOf(value).isBlank())throw new IllegalStateException("Approval binding missing");ticket.put(key,value);
                }
                long until=((Number)proposal.body().get("expiresAtMs")).longValue();expires[0]=Math.min(expires[0],until);
                tickets.add(Map.copyOf(ticket));
                var visible=new LinkedHashMap<>(preview.body());visible.remove("dryRunPreview");
                previews.add(visible);
            }return null;
        });
        return new Prepared(plan,List.copyOf(previews),List.copyOf(tickets),expires[0]);
    }
    public Map<String,Object> execute(TeamRunStore.Run run,Step step,Map<String,Object> ticket){
        return context(run,()->{
            var result=writes(step.probe())?pipeline.executeConfirmed(String.valueOf(ticket.get("confirmationId")),
                    String.valueOf(ticket.get("capabilityToken")),String.valueOf(ticket.get("confirmToken")),"确认执行",String.valueOf(run.userId()),run.id())
                    :pipeline.executeDirect(step.tool(),step.parameters(),run.goal(),String.valueOf(run.userId()));
            return result.body();
        });
    }
    public Set<String> resources(Plan plan){
        Set<String> resources=new TreeSet<>();
        for(Step step:plan.steps())if(writes(step.probe())){
            var effect=effects.resolve(step.tool(),realParams(step.probe()));
            // Conservatively serialize all filesystem writes to cover ancestor/descendant conflicts within Teams.
            resources.add("PATH".equals(effect.targetType())?"LOCAL_FILESYSTEM":effect.targetType()+":"+ConfirmTokenHmac.hashPreview(effect.targetId().toLowerCase(Locale.ROOT)));
        }
        return resources;
    }
}
