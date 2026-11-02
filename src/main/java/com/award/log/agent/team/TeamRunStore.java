package com.award.log.agent.team;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import com.award.log.security.effect.ConfirmTokenHmac;

/** Durable role transitions and write intents. No automatic write replay after interruption. */
@Service
public class TeamRunStore {
    public static final Set<String> ACTIVE=Set.of("QUEUED","COLLECTING","DIAGNOSING","PLANNING","PREVIEWING","EXECUTING","VERIFYING");
    public record Run(String id,int userId,String goal,String skillId,String release,String skillJson,String probesJson,
                      String state,int revision,String execution,Instant deadline,long remainingMs,int modelCalls,
                      String evidenceJson,String diagnosisJson,String planJson,String planHash,String error,boolean writeStarted) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TeamDataPolicy data;
    private final String instance=UUID.randomUUID().toString();
    private final long budget;
    private final int maxCalls;
    public TeamRunStore(JdbcTemplate jdbc,PlatformTransactionManager transactions,TeamDataPolicy data,
                        @Value("${agent.teams.active-budget-ms:180000}") long budget,
                        @Value("${agent.teams.max-model-calls:6}") int maxCalls) {
        if(budget<1000||budget>600000||maxCalls<3||maxCalls>12)throw new IllegalArgumentException("Invalid team budgets");
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.data=data;this.budget=budget;this.maxCalls=maxCalls;
    }
    private Timestamp now(){return Timestamp.from(Instant.now());}
    private <T>T locked(String id,Supplier<T> work){return tx.execute(s->{jdbc.queryForList("SELECT run_id FROM ops_team_run WHERE run_id=? FOR UPDATE",id);return work.get();});}
    private Run row(String id){
        var rows=jdbc.queryForList("SELECT * FROM ops_team_run WHERE run_id=?",id);
        if(rows.isEmpty())throw new NoSuchElementException("Team run not found");
        var r=rows.get(0);
        return new Run(id,((Number)r.get("user_id")).intValue(),(String)r.get("goal"),(String)r.get("skill_id"),
                (String)r.get("skill_release"),(String)r.get("skill_json"),(String)r.get("probes_json"),(String)r.get("state"),
                ((Number)r.get("revision")).intValue(),(String)r.get("execution_id"),
                r.get("deadline_at")==null?null:((Timestamp)r.get("deadline_at")).toInstant(),
                ((Number)r.get("remaining_ms")).longValue(),((Number)r.get("model_calls")).intValue(),
                (String)r.get("evidence_json"),(String)r.get("diagnosis_json"),(String)r.get("plan_json"),
                (String)r.get("plan_hash"),(String)r.get("error_code"),Boolean.TRUE.equals(r.get("write_started")));
    }
    public Run get(String id){return row(id);}
    public Run owned(String id,int user,boolean admin){Run run=row(id);if(!admin&&run.userId()!=user)throw new NoSuchElementException("Team run not found");return run;}
    public List<Map<String,Object>> list(int user,boolean admin){return jdbc.queryForList(
            "SELECT run_id,goal,skill_id,state,revision,error_code,created_at FROM ops_team_run "+(admin?"":"WHERE user_id=? ")+"ORDER BY created_at DESC LIMIT 50",admin?new Object[]{}:new Object[]{user});}
    public List<Map<String,Object>> events(String id){return jdbc.queryForList("SELECT event_no,actor,kind,payload,created_at FROM ops_team_event WHERE run_id=? ORDER BY event_no LIMIT 1024",id);}
    private void event(Run run,String actor,String kind,Object payload){
        Integer n=jdbc.queryForObject("SELECT next_event FROM ops_team_run WHERE run_id=?",Integer.class,run.id());
        // Revision/call/step limits keep normal runs below this reserve, including late receipts.
        if(n==null||n>1024)throw new IllegalStateException("Team event budget exhausted");
        jdbc.update("INSERT INTO ops_team_event(run_id,event_no,execution_id,actor,kind,payload) VALUES(?,?,?,?,?,?)",
                run.id(),n,run.execution(),actor,kind,data.safe(payload));
        jdbc.update("UPDATE ops_team_run SET next_event=next_event+1,updated_at=? WHERE run_id=?",now(),run.id());
    }
    public Run create(String id,int user,String requestId,String goal,String skillId,String release,String skillJson,String probes){
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid request id");
        String hash=ConfirmTokenHmac.hashPreview(data.encode(List.of(goal,skillId,probes)));
        try { return tx.execute(s->{
            var prior=jdbc.queryForList("SELECT run_id,request_hash FROM ops_team_run WHERE user_id=? AND request_id=?",user,requestId);
            if(!prior.isEmpty()){
                if(!hash.equals(prior.get(0).get("request_hash")))throw new IllegalStateException("Request content changed");
                return row((String)prior.get(0).get("run_id"));
            }
            jdbc.update("INSERT INTO ops_team_run(run_id,user_id,request_id,request_hash,goal,skill_id,skill_release,skill_json,probes_json,state,execution_id,instance_id,deadline_at,window_started,remaining_ms) "
                    +"VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?)",id,user,requestId,hash,goal,skillId,release,skillJson,probes,UUID.randomUUID().toString(),instance,
                    Timestamp.from(Instant.now().plusMillis(budget)),now(),budget);
            Run run=row(id);event(run,String.valueOf(user),"CREATED",Map.of("skillRelease",release,"skill",skillId));return run;
        }); } catch(org.springframework.dao.DuplicateKeyException conflict) {
            var prior=jdbc.queryForList("SELECT run_id,request_hash FROM ops_team_run WHERE user_id=? AND request_id=?",user,requestId);
            if(prior.isEmpty())throw conflict;
            if(!hash.equals(prior.get(0).get("request_hash")))throw new IllegalStateException("Request content changed");
            return row((String)prior.get(0).get("run_id"));
        }
    }
    private Run active(String id,String token){
        Run run=row(id);
        if(!run.execution().equals(token)||!ACTIVE.contains(run.state())||run.deadline()==null||!run.deadline().isAfter(Instant.now()))
            throw new IllegalStateException("Team execution is no longer current");
        if(!instance.equals(jdbc.queryForObject("SELECT instance_id FROM ops_team_run WHERE run_id=?",String.class,id)))
            throw new IllegalStateException("Team execution belongs to another runtime instance");
        return run;
    }
    public boolean claim(Run run){return locked(run.id(),()->{
        try{active(run.id(),run.execution());}catch(IllegalStateException e){return false;}
        return jdbc.update("UPDATE ops_team_run SET worker_claimed=TRUE WHERE run_id=? AND worker_claimed=FALSE AND instance_id=?",run.id(),instance)>0;
    });}
    public boolean current(Run run){try{return locked(run.id(),()->{active(run.id(),run.execution());return true;});}catch(IllegalStateException|NoSuchElementException e){return false;}}
    public Run advance(String id,String token,String state,Map<String,String> fields){
        if(!ACTIVE.contains(state))throw new IllegalArgumentException("Invalid active phase");
        return locked(id,()->{
            Run run=active(id,token);
            for(var field:fields.entrySet()){
                if(!Set.of("evidence_json","diagnosis_json","plan_json","plan_hash").contains(field.getKey()))throw new IllegalArgumentException("Invalid team field");
                data.bounded(field.getValue(),60000);jdbc.update("UPDATE ops_team_run SET "+field.getKey()+"=? WHERE run_id=?",field.getValue(),id);
            }
            jdbc.update("UPDATE ops_team_run SET state=?,revision=revision+1,error_code=NULL WHERE run_id=?",state,id);
            event(run,"SYSTEM","PHASE",Map.of("state",state));return row(id);
        });
    }
    public void evidence(Run run,List<TeamProtocol.Evidence> evidence){
        locked(run.id(),()->{Run live=active(run.id(),run.execution());String encoded=data.safe(evidence);
            jdbc.update("UPDATE ops_team_run SET evidence_json=? WHERE run_id=?",encoded,run.id());event(live,"SYSTEM","EVIDENCE",evidence);return null;});
    }
    public void reserveModel(Run run,String role,Object input){
        locked(run.id(),()->{Run live=active(run.id(),run.execution());if(live.modelCalls()>=maxCalls)throw new IllegalStateException("Model call budget exhausted");
            jdbc.update("UPDATE ops_team_run SET model_calls=model_calls+1 WHERE run_id=?",run.id());event(live,role,"MODEL_INPUT",input);return null;});
    }
    public void roleResult(Run run,String role,TeamProtocol.ModelReply reply){
        locked(run.id(),()->{Run live=active(run.id(),run.execution());event(live,role,"MODEL_OUTPUT",Map.of("model",reply.model(),"durationMs",reply.durationMs(),"output",data.tree(reply.json())));return null;});
    }
    private long remaining(Run run){
        var start=jdbc.queryForObject("SELECT window_started FROM ops_team_run WHERE run_id=?",Timestamp.class,run.id());
        return Math.max(0,run.remainingMs()-(start==null?0:Math.max(0,Duration.between(start.toInstant(),Instant.now()).toMillis())));
    }
    public Run pause(String id,String token,String state,String error){
        if(!Set.of("WAITING_APPROVAL","NEEDS_HUMAN","COMPLETED","CANCELLED").contains(state))throw new IllegalArgumentException("Invalid paused state");
        return locked(id,()->{Run run=row(id);if(!run.execution().equals(token)||!ACTIVE.contains(run.state()))return run;
            if(!"NEEDS_HUMAN".equals(state))active(id,token);
            jdbc.update("UPDATE ops_team_run SET state=?,error_code=?,remaining_ms=?,deadline_at=NULL,window_started=NULL,revision=revision+1 WHERE run_id=?",state,error,remaining(run),id);
            event(run,"SYSTEM","STATE",Map.of("state",state,"reason",error==null?"":error));
            if("COMPLETED".equals(state))jdbc.update("DELETE FROM ops_team_resource_lock WHERE run_id=?",id);
            return row(id);
        });
    }
    public Run approve(String id,int revision,String hash,String actor){
        return locked(id,()->{Run run=row(id);
            if(!"WAITING_APPROVAL".equals(run.state())||run.revision()!=revision||!Objects.equals(run.planHash(),hash)||run.remainingMs()<100)
                throw new IllegalStateException("Plan changed or active budget exhausted");
            return newWindow(run,"EXECUTING",actor,"HUMAN_APPROVED",Map.of("planHash",hash));
        });
    }
    private Run newWindow(Run run,String state,String actor,String kind,Object payload){
        jdbc.update("UPDATE ops_team_run SET state=?,execution_id=?,instance_id=?,deadline_at=?,window_started=?,worker_claimed=FALSE,revision=revision+1,error_code=NULL WHERE run_id=?",
                state,UUID.randomUUID().toString(),instance,Timestamp.from(Instant.now().plusMillis(run.remainingMs())),now(),run.id());
        Run next=row(run.id());event(next,actor,kind,payload);return next;
    }
    public Run edit(String id,int revision,String plan,String reason,String actor,boolean reconciled){
        return locked(id,()->{Run run=row(id);
            if(!Set.of("WAITING_APPROVAL","NEEDS_HUMAN").contains(run.state())||run.revision()!=revision||run.revision()>100||run.remainingMs()<100)
                throw new IllegalStateException("Team run cannot accept a new plan");
            Integer held=jdbc.queryForObject("SELECT COUNT(*) FROM ops_team_resource_lock WHERE run_id=?",Integer.class,id);
            if(run.writeStarted()&&held!=null&&held>0&&!reconciled)throw new IllegalStateException("External effects must be reconciled first");
            if(reconciled)jdbc.update("DELETE FROM ops_team_resource_lock WHERE run_id=?",id);
            jdbc.update("UPDATE ops_team_run SET plan_json=?,plan_hash=?,diagnosis_json=NULL,evidence_json=NULL WHERE run_id=?",plan,ConfirmTokenHmac.hashPreview(plan),id);
            return newWindow(row(id),"PREVIEWING",actor,"HUMAN_PLAN",Map.of("reason",reason,"externalEffectsReviewed",reconciled,"plan",data.tree(plan)));
        });
    }
    public Run retryEvidence(String id,int revision,String probes,String reason,String actor,boolean reconciled){
        return locked(id,()->{Run run=row(id);
            if(!"NEEDS_HUMAN".equals(run.state())||run.revision()!=revision||run.revision()>100||run.remainingMs()<100||run.modelCalls()+3>maxCalls)
                throw new IllegalStateException("Team run cannot retry evidence within its remaining budget");
            Integer held=jdbc.queryForObject("SELECT COUNT(*) FROM ops_team_resource_lock WHERE run_id=?",Integer.class,id);
            if(run.writeStarted()&&held!=null&&held>0&&!reconciled)throw new IllegalStateException("External effects must be reconciled first");
            if(reconciled)jdbc.update("DELETE FROM ops_team_resource_lock WHERE run_id=?",id);
            jdbc.update("UPDATE ops_team_run SET probes_json=?,plan_json=NULL,plan_hash=NULL,diagnosis_json=NULL,evidence_json=NULL WHERE run_id=?",probes,id);
            return newWindow(row(id),"COLLECTING",actor,"HUMAN_EVIDENCE_RETRY",Map.of("reason",reason,"externalEffectsReviewed",reconciled,"observations",data.tree(probes)));
        });
    }
    public Run cancel(String id,String actor){
        return locked(id,()->{Run run=row(id);if(Set.of("COMPLETED","CANCELLED").contains(run.state())
                ||"NEEDS_HUMAN".equals(run.state())&&"CANCELLED_AFTER_WRITE_INTENT".equals(run.error()))return run;
            String state=run.writeStarted()?"NEEDS_HUMAN":"CANCELLED";
            jdbc.update("UPDATE ops_team_run SET state=?,error_code=?,remaining_ms=?,deadline_at=NULL,window_started=NULL,revision=revision+1 WHERE run_id=?",
                    state,run.writeStarted()?"CANCELLED_AFTER_WRITE_INTENT":"USER_CANCELLED",ACTIVE.contains(run.state())?remaining(run):run.remainingMs(),id);
            event(run,actor,"CANCEL_REQUESTED",Map.of("state",state));
            if(!run.writeStarted())jdbc.update("DELETE FROM ops_team_resource_lock WHERE run_id=?",id);return row(id);
        });
    }
    public void acquire(Run run,Set<String> resources){
        locked(run.id(),()->{active(run.id(),run.execution());for(String resource:new TreeSet<>(resources)){
            var held=jdbc.queryForList("SELECT run_id FROM ops_team_resource_lock WHERE resource_key=? FOR UPDATE",resource);
            if(!held.isEmpty()&&!run.id().equals(held.get(0).get("run_id")))throw new IllegalStateException("Resource requires another run's reconciliation");
            if(held.isEmpty())jdbc.update("INSERT INTO ops_team_resource_lock(resource_key,run_id,execution_id) VALUES(?,?,?)",resource,run.id(),run.execution());
        }return null;});
    }
    public void intent(Run run,int step,Object payload){locked(run.id(),()->{Run live=active(run.id(),run.execution());
        jdbc.update("UPDATE ops_team_run SET write_started=TRUE WHERE run_id=?",run.id());event(live,"EXECUTOR","WRITE_INTENT",Map.of("step",step,"request",payload));return null;});}
    public void receipt(Run run,int step,Object payload){locked(run.id(),()->{
        // Late receipts are append-only evidence, never a terminal-state overwrite.
        event(run,"EXECUTOR","TOOL_RECEIPT",Map.of("step",step,"execution",run.execution(),"result",payload));return null;});}
    public void workerFailure(Run run,String code,RuntimeException failure){locked(run.id(),()->{
        Run live=row(run.id());
        if(!run.execution().equals(live.execution()))return null;
        String message=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        event(live,"SYSTEM","WORKER_FAILURE",Map.of("state",live.state(),"code",code,"type",failure.getClass().getSimpleName(),
                "message",data.clip(data.redact(message),1200)));
        return null;
    });}
    public void reconcileExpired(){
        var ids=jdbc.queryForList("SELECT run_id FROM ops_team_run WHERE deadline_at<=? LIMIT 100",String.class,now());
        for(String id:ids){Run run=get(id);pause(id,run.execution(),"NEEDS_HUMAN","RUNTIME_DEADLINE_OR_INTERRUPTION");}
    }
}
