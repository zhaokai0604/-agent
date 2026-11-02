package com.award.log.agent.team;

import static com.award.log.agent.team.TeamProtocol.*;
import com.award.log.agent.skill.SkillCatalogLoader;
import com.award.log.agent.skill.SkillCatalogLoader.YamlSkillDefinition;
import com.award.log.security.effect.ConfirmTokenHmac;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/** Diagnosis -> plan -> human approval -> governed execution -> independent post-state verification. */
@Service
public class AgentTeamService {
    private static final class TeamFailure extends IllegalStateException {
        private final String code;
        private TeamFailure(String code) { super(code); this.code=code; }
    }
    private record Bundle(int revision,String hash,TeamToolGateway.Prepared prepared) {}
    private record Worker(TeamRunStore.Run run,FutureTask<Void> future,java.util.concurrent.atomic.AtomicBoolean running) {}
    private final TeamRunStore store;
    private final TeamDataPolicy data;
    private final SkillCatalogLoader skills;
    private final TeamToolGateway tools;
    private final TeamRoleModel model;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String,Bundle> approvals=new ConcurrentHashMap<>();
    private final Map<String,Worker> workers=new ConcurrentHashMap<>();
    private volatile boolean closing;
    public AgentTeamService(TeamRunStore store,TeamDataPolicy data,SkillCatalogLoader skills,TeamToolGateway tools,TeamRoleModel model,
                            @Qualifier("agentTeamExecutor") ThreadPoolTaskExecutor executor){
        this.store=store;this.data=data;this.skills=skills;this.tools=tools;this.model=model;this.executor=executor;
    }
    public TeamRunStore.Run start(int user,String requestId,String goal,String skillId,List<Probe> probes){
        if(closing)throw new IllegalStateException("Team runtime is closing");
        if(goal==null||goal.isBlank())throw new IllegalArgumentException("Team goal is required");
        data.bounded(goal,4000);goal=data.redact(goal);
        String id=UUID.randomUUID().toString();TeamRunStore.Run run;
        try(var pin=skills.pinRun(id,String.valueOf(user))){
            var skill=skills.find(skillId);if(skill==null)throw new IllegalArgumentException("Unknown Skill");
            tools.validateProbes(probes,skill);
            run=store.create(id,user,requestId,goal,skillId,String.valueOf(skills.status().get("catalogFingerprint")),data.encode(skill),data.encode(probes));
        }
        if("QUEUED".equals(run.state()))submit(run,false,null);
        return store.get(run.id());
    }
    public TeamRunStore.Run owned(String id,int user,boolean admin){return store.owned(id,user,admin);}
    public Map<String,Object> view(TeamRunStore.Run run){
        var out=new LinkedHashMap<String,Object>();
        out.put("runId",run.id());out.put("goal",run.goal());out.put("skillId",run.skillId());out.put("skillRelease",run.release());
        out.put("state",run.state());out.put("revision",run.revision());out.put("remainingMs",run.remainingMs());out.put("modelCalls",run.modelCalls());
        out.put("planHash",run.planHash());out.put("errorCode",run.error());out.put("writeStarted",run.writeStarted());
        out.put("plan",run.planJson()==null?null:data.tree(run.planJson()));
        out.put("diagnosis",run.diagnosisJson()==null?null:data.tree(run.diagnosisJson()));
        out.put("observations",data.tree(run.probesJson()));
        out.put("evidence",run.evidenceJson()==null?List.of():data.tree(run.evidenceJson()));
        Bundle bundle;
        synchronized(approvals){bundle=approvals.get(run.id());}
        boolean ready=bundle!=null&&bundle.revision()==run.revision()&&bundle.hash().equals(run.planHash())&&bundle.prepared().expiresAt()>System.currentTimeMillis();
        out.put("approvalReady",ready);out.put("previews",ready?bundle.prepared().previews():List.of());out.put("events",store.events(run.id()));return out;
    }
    public List<Map<String,Object>> list(int user,boolean admin){return store.list(user,admin);}
    public TeamRunStore.Run approve(String id,int revision,String hash,String code,String actor){
        if(!"确认执行".equals(code))throw new IllegalArgumentException("Explicit confirmation is required");
        Bundle bundle=approvals.get(id);
        if(bundle==null||bundle.revision()!=revision||!bundle.hash().equals(hash)||bundle.prepared().expiresAt()<=System.currentTimeMillis()
                ||!approvals.remove(id,bundle))throw new IllegalStateException("Approval expired; preview the plan again");
        var run=store.approve(id,revision,hash,actor);submit(run,true,bundle.prepared());return store.get(id);
    }
    public TeamRunStore.Run edit(String id,int revision,Plan plan,String reason,String actor,boolean reconciled){
        if(reason==null||reason.isBlank())throw new IllegalArgumentException("A takeover reason is required");
        data.bounded(reason,1000);TeamRunStore.Run current=store.get(id);
        if(workers.values().stream().anyMatch(w->w.run().id().equals(id)&&w.running().get()))
            throw new IllegalStateException("Previous worker must exit before takeover");
        tools.validate(plan,data.decode(current.skillJson(),YamlSkillDefinition.class));
        var run=store.edit(id,revision,data.encode(plan),data.redact(reason),actor,reconciled);
        approvals.remove(id);submit(run,false,null);return store.get(id);
    }
    public TeamRunStore.Run cancel(String id,String actor){
        var run=store.cancel(id,actor);approvals.remove(id);
        workers.forEach((token,w)->{if(w.run().id().equals(id)){w.future().cancel(true);if(!w.running().get())workers.remove(token,w);}});return run;
    }
    public TeamRunStore.Run retryEvidence(String id,int revision,List<Probe> probes,String reason,String actor,boolean reconciled){
        if(reason==null||reason.isBlank())throw new IllegalArgumentException("A takeover reason is required");
        data.bounded(reason,1000);var current=store.get(id);
        if(workers.values().stream().anyMatch(w->w.run().id().equals(id)&&w.running().get()))
            throw new IllegalStateException("Previous worker must exit before takeover");
        tools.validateProbes(probes,data.decode(current.skillJson(),YamlSkillDefinition.class));
        var run=store.retryEvidence(id,revision,data.encode(probes),data.redact(reason),actor,reconciled);
        approvals.remove(id);submit(run,false,null);return store.get(id);
    }
    private void submit(TeamRunStore.Run run,boolean execute,TeamToolGateway.Prepared prepared){
        var running=new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<Void> future=new FutureTask<>(()->{running.set(true);try{work(run,execute,prepared);return null;}finally{running.set(false);}});
        if(workers.putIfAbsent(run.execution(),new Worker(run,future,running))!=null)return;
        if(closing){workers.remove(run.execution());future.cancel(true);store.pause(run.id(),run.execution(),"NEEDS_HUMAN","RUNTIME_SHUTDOWN");return;}
        try{executor.execute(future);}catch(RuntimeException rejected){workers.remove(run.execution());future.cancel(true);store.pause(run.id(),run.execution(),"NEEDS_HUMAN","QUEUE_REJECTED");}
    }
    private List<Probe> probes(TeamRunStore.Run run){return Arrays.asList(data.decode(run.probesJson(),Probe[].class));}
    private List<Evidence> evidence(TeamRunStore.Run run){return run.evidenceJson()==null?List.of():Arrays.asList(data.decode(run.evidenceJson(),Evidence[].class));}
    private TeamRunStore.Run phase(TeamRunStore.Run run,String phase,Map<String,String> values){return store.advance(run.id(),run.execution(),phase,values);}
    private List<Evidence> collect(TeamRunStore.Run run,List<Probe> probes,String prefix){
        List<Evidence> out=new ArrayList<>();int i=0;
        for(Probe probe:probes){if(!store.current(run)||Thread.currentThread().isInterrupted())throw new IllegalStateException("Team no longer active");out.add(tools.observe(run,probe,prefix+(++i)));}
        return List.copyOf(out);
    }
    private ModelReply ask(TeamRunStore.Run run,String role,Object input){
        store.reserveModel(run,role,input);var reply=model.call(role,input,run.deadline());store.roleResult(run,role,reply);return reply;
    }
    private void references(List<String> ids,List<Evidence> evidence,int min){
        if(ids==null||ids.isEmpty()||ids.size()>6)throw new TeamFailure("EVIDENCE_REFERENCES_MISSING");
        Map<String,Evidence> known=new HashMap<>();evidence.forEach(e->known.put(e.id(),e));Set<String> sources=new HashSet<>();
        for(String id:ids){if(!known.containsKey(id))throw new TeamFailure("EVIDENCE_REFERENCE_UNKNOWN");sources.add(known.get(id).source());}
        if(sources.size()<min)throw new TeamFailure("EVIDENCE_INSUFFICIENT");
    }
    private void work(TeamRunStore.Run initial,boolean execute,TeamToolGateway.Prepared prepared){
        try{
            if(!store.claim(initial))return;
            try(var pin=skills.pinRun(initial.id(),String.valueOf(initial.userId()))){
                if(!initial.release().equals(String.valueOf(skills.status().get("catalogFingerprint"))))throw new IllegalStateException("Pinned release changed");
                var skill=data.decode(initial.skillJson(),YamlSkillDefinition.class);
                if(execute)execute(initial,prepared,skill);else plan(initial,skill);
            }
        }catch(RuntimeException error){
            String code=closing?"RUNTIME_SHUTDOWN":Thread.currentThread().isInterrupted()?"EXECUTION_INTERRUPTED":error instanceof TeamFailure failure?failure.code:error instanceof IllegalArgumentException?"ROLE_OR_TOOL_CONTRACT_INVALID":"ROLE_TOOL_OR_BUDGET_FAILED";
            store.workerFailure(initial,code,error);
            store.pause(initial.id(),initial.execution(),"NEEDS_HUMAN",code);
        }finally{workers.remove(initial.execution());}
    }
    private void plan(TeamRunStore.Run run,YamlSkillDefinition skill){
        run=phase(run,"COLLECTING",Map.of());
        var evidence=collect(run,probes(run),"pre-");store.evidence(run,evidence);
        Plan plan;
        if(run.planJson()==null){
            run=phase(run,"DIAGNOSING",Map.of());
            var diagnosis=data.decode(ask(run,"DIAGNOSIS",Map.of("goal",run.goal(),"skill",skill,"evidence",evidence)).json(),Diagnosis.class);
            if(diagnosis.summary()==null||diagnosis.summary().isBlank())throw new TeamFailure("DIAGNOSIS_EMPTY");
            if(diagnosis.needsMoreEvidence())throw new TeamFailure("DIAGNOSIS_NEEDS_EVIDENCE");
            references(diagnosis.evidenceIds(),evidence,skill.minIndependentSignals());
            run=phase(run,"PLANNING",Map.of("diagnosis_json",data.safe(diagnosis)));
            var verificationTools=skill.tools().stream().filter(com.award.log.security.effect.ToolOperationPolicy::isReadOnlyOnlyTool).toList();
            plan=data.decode(ask(run,"PLANNER",Map.of("goal",run.goal(),"skill",skill,"evidence",evidence,"diagnosis",diagnosis,"verificationTools",verificationTools)).json(),Plan.class);
        }else plan=data.decode(run.planJson(),Plan.class);
        tools.validate(plan,skill);
        // Human plans use the same evidence floor and cannot authorize arbitrary tools.
        if(evidence.stream().map(Evidence::source).distinct().count()<skill.minIndependentSignals())throw new TeamFailure("EVIDENCE_INSUFFICIENT");
        String encoded=data.encode(plan);run=phase(run,"PREVIEWING",Map.of("plan_json",encoded,"plan_hash",ConfirmTokenHmac.hashPreview(encoded)));
        var prepared=tools.prepare(run,plan);
        synchronized(approvals){
            if(approvals.size()>=128)throw new IllegalStateException("Pending team approval capacity reached");
            var waiting=store.pause(run.id(),run.execution(),"WAITING_APPROVAL",null);
            if("WAITING_APPROVAL".equals(waiting.state()))approvals.put(run.id(),new Bundle(waiting.revision(),waiting.planHash(),prepared));
        }
    }
    private void execute(TeamRunStore.Run run,TeamToolGateway.Prepared prepared,YamlSkillDefinition skill){
        if(prepared==null)throw new IllegalStateException("No in-process approval binding");
        Plan plan=data.decode(run.planJson(),Plan.class);tools.validate(plan,skill);
        if(!data.encode(prepared.plan()).equals(run.planJson()))throw new IllegalStateException("Approved plan differs");
        store.acquire(run,tools.resources(plan));
        List<Map<String,Object>> receipts=new ArrayList<>();int index=0;
        for(Step step:plan.steps()){
            if(!store.current(run)||Thread.currentThread().isInterrupted())throw new IllegalStateException("Execution stopped");
            if(tools.writes(step.probe()))store.intent(run,index,Map.of("tool",step.tool(),"parameters",step.parameters(),"expectedOutcome",step.expectedOutcome()));
            var receipt=tools.execute(run,step,prepared.tickets().get(index));store.receipt(run,index++,receipt);receipts.add(receipt);
            if(!Boolean.TRUE.equals(receipt.get("success"))||tools.writes(step.probe())&&!"VERIFIED".equals(receipt.get("verifyOutcome")))
                throw new TeamFailure("TOOL_EFFECT_NOT_VERIFIED");
        }
        run=phase(run,"VERIFYING",Map.of());
        var after=collect(run,plan.verification(),"post-");
        var before=evidence(run);List<Evidence> all=new ArrayList<>(before);all.addAll(after);store.evidence(run,all);
        var result=data.decode(ask(run,"VERIFIER",Map.of("goal",run.goal(),"skill",skill,"plan",plan,"preEvidence",before,"receipts",receipts,"postEvidence",after)).json(),Verification.class);
        references(result.evidenceIds(),after,1);
        if(result.summary()==null||result.summary().isBlank())throw new TeamFailure("VERIFICATION_EMPTY");
        if(!"VERIFIED".equals(result.verdict()))throw new TeamFailure("VERIFIER_REQUESTED_TAKEOVER");
        store.pause(run.id(),run.execution(),"COMPLETED",null);
    }
    @EventListener(ApplicationReadyEvent.class) public void startup(){store.reconcileExpired();}
    @Scheduled(fixedDelayString="${agent.teams.maintenance-ms:3000}") public void reconcile(){
        if(closing)return;store.reconcileExpired();
        workers.forEach((id,worker)->{if(!store.current(worker.run())){worker.future().cancel(true);if(!worker.running().get())workers.remove(id,worker);}});
        approvals.entrySet().removeIf(e->e.getValue().prepared().expiresAt()<=System.currentTimeMillis());
    }
    @PreDestroy public void close(){closing=true;workers.values().forEach(w->{store.pause(w.run().id(),w.run().execution(),"NEEDS_HUMAN","RUNTIME_SHUTDOWN");w.future().cancel(true);});workers.clear();approvals.clear();}
}
