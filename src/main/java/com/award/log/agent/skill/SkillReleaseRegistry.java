package com.award.log.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Shared persistent release head, immutable artifacts, activation history and run pins. */
@Service
public class SkillReleaseRegistry {
    public record Active(String fingerprint,String previous,long generation,String payload) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SkillReleaseEvaluator evaluator;
    private final ObjectMapper json=new ObjectMapper();
    public SkillReleaseRegistry(JdbcTemplate jdbc,PlatformTransactionManager transactions,SkillReleaseEvaluator evaluator) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);this.evaluator=evaluator;
    }
    public Active current() {
        var rows=jdbc.queryForList("SELECT a.fingerprint,a.previous_fingerprint,a.generation,r.payload FROM ops_skill_active a LEFT JOIN ops_skill_release r ON r.fingerprint=a.fingerprint WHERE singleton_id=1");
        if(rows.size()!=1)throw new IllegalStateException("Skill release head is missing");
        var r=rows.get(0);
        if(r.get("fingerprint")==null)return null;
        if(r.get("payload")==null)throw new IllegalStateException("Skill active artifact is missing");
        return new Active((String)r.get("fingerprint"),(String)r.get("previous_fingerprint"),((Number)r.get("generation")).longValue(),(String)r.get("payload"));
    }
    private void lockHead(){jdbc.queryForList("SELECT singleton_id FROM ops_skill_active WHERE singleton_id=1 FOR UPDATE");}
    public Active bootstrap(Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions) {
        var fixed=SkillCatalogLoader.decode(serialize(new TreeMap<>(definitions)));
        return tx.execute(ignored->{lockHead();var existing=current();if(existing!=null)return existing;
            return publishLocked(null,fixed,"SYSTEM","Initial validated deployment catalog","BOOTSTRAP");});
    }
    public Map<String,Object> evaluate(Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions){
        var result=new LinkedHashMap<>(evaluator.evaluate(definitions));
        var failures=new ArrayList<>((List<String>)result.get("failures"));
        var active=current();var old=active==null?Map.<String,SkillCatalogLoader.YamlSkillDefinition>of():SkillCatalogLoader.decode(active.payload());
        for(var def:definitions.values()) {
            var previous=old.get(def.id());
            if(previous!=null&&SkillCatalogLoader.compareVersion(def.version(),previous.version())<0)failures.add(def.id()+": use explicit rollback for older versions");
            var stored=jdbc.queryForList("SELECT contract_fingerprint FROM ops_skill_version WHERE skill_id=? AND version=?",String.class,def.id(),def.version());
            if(!stored.isEmpty()&&!stored.get(0).equals(def.fingerprint()))failures.add(def.id()+": same-version content drift");
        }
        result.put("failures",List.copyOf(failures));result.put("passed",failures.isEmpty());return result;
    }
    public Active publish(String expected,Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions,String actor,String reason) {
        validateActor(actor,reason);
        var fixed=SkillCatalogLoader.decode(serialize(new TreeMap<>(definitions)));
        return tx.execute(ignored->{lockHead();return publishLocked(expected,fixed,actor,reason,"PUBLISH");});
    }
    private Active publishLocked(String expected,Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions,String actor,String reason,String action) {
        var old=current();
        if(!Objects.equals(expected,old==null?null:old.fingerprint()))throw new IllegalStateException("Skill catalog changed; preview again");
        Map<String,Object> evaluation=evaluate(definitions);
        if(!Boolean.TRUE.equals(evaluation.get("passed")))throw new IllegalStateException("Skill release evaluation failed: "+evaluation.get("failures"));
        var oldDefinitions=old==null?Map.<String,SkillCatalogLoader.YamlSkillDefinition>of():SkillCatalogLoader.decode(old.payload());
        for(var def:definitions.values()) {
            var previous=oldDefinitions.get(def.id());
            if(previous!=null && SkillCatalogLoader.compareVersion(def.version(),previous.version())<0)throw new IllegalStateException("Use explicit rollback for older Skill versions");
            var versions=jdbc.queryForList("SELECT contract_fingerprint FROM ops_skill_version WHERE skill_id=? AND version=?",String.class,def.id(),def.version());
            if(!versions.isEmpty()&&!def.fingerprint().equals(versions.get(0)))throw new IllegalStateException("Skill version content drift: "+def.id());
        }
        String fingerprint=SkillCatalogLoader.fingerprint(definitions);
        if(old!=null&&old.fingerprint().equals(fingerprint))return old;
        var storedArtifacts=jdbc.queryForList("SELECT payload FROM ops_skill_release WHERE fingerprint=?",String.class,fingerprint);
        if(!storedArtifacts.isEmpty()&&!fingerprint.equals(SkillCatalogLoader.fingerprint(SkillCatalogLoader.decode(storedArtifacts.get(0))))) {
            throw new IllegalStateException("Stored Skill release fingerprint changed; activation refused");
        }
        if(storedArtifacts.isEmpty()) {
            jdbc.update("INSERT INTO ops_skill_release(fingerprint,payload,skill_count,evaluation_json,created_by) VALUES(?,?,?,?,?)",
                    fingerprint,serialize(new TreeMap<>(definitions)),definitions.size(),serialize(evaluation),actor);
            for(var def:definitions.values())if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_skill_version WHERE skill_id=? AND version=?",Integer.class,def.id(),def.version())==0) {
                jdbc.update("INSERT INTO ops_skill_version(skill_id,version,contract_fingerprint,first_release) VALUES(?,?,?,?)",def.id(),def.version(),def.fingerprint(),fingerprint);
            }
        }
        activate(old,fingerprint,actor,reason,action);
        return current();
    }
    private void activate(Active old,String target,String actor,String reason,String action) {
        long generation=old==null?1:old.generation()+1;
        jdbc.update("UPDATE ops_skill_active SET fingerprint=?,previous_fingerprint=?,generation=?,activated_by=?,activated_at=CURRENT_TIMESTAMP WHERE singleton_id=1",
                target,old==null?null:old.fingerprint(),generation,actor);
        jdbc.update("INSERT INTO ops_skill_activation(from_fingerprint,to_fingerprint,action,actor,reason,generation) VALUES(?,?,?,?,?,?)",
                old==null?null:old.fingerprint(),target,action,actor,reason,generation);
    }
    public Active rollback(String expected,String target,String actor,String reason) {
        validateActor(actor,reason);
        return tx.execute(ignored->{lockHead();var old=current();
            if(old==null||!old.fingerprint().equals(expected))throw new IllegalStateException("Skill catalog changed; preview again");
            String selected=target==null||target.isBlank()?old.previous():target;
            if(selected==null||selected.equals(old.fingerprint()))throw new IllegalStateException("No different release to restore");
            var payloads=jdbc.queryForList("SELECT payload FROM ops_skill_release WHERE fingerprint=?",String.class,selected);
            if(payloads.size()!=1)throw new IllegalStateException("Unknown Skill release");
            var definitions=SkillCatalogLoader.decode(payloads.get(0));
            if(!selected.equals(SkillCatalogLoader.fingerprint(definitions))||!Boolean.TRUE.equals(evaluator.evaluate(definitions).get("passed")))throw new IllegalStateException("Rollback artifact failed current contract evaluation");
            activate(old,selected,actor,reason,"ROLLBACK");return current();});
    }
    public Active pin(String runId,String requester) {
        if(runId==null||!runId.matches("[A-Za-z0-9._:-]{1,64}")||requester==null||requester.isBlank()||requester.length()>64)throw new IllegalArgumentException("Invalid Skill run identity");
        return tx.execute(ignored->{lockHead();var active=current();if(active==null)throw new IllegalStateException("No active Skill catalog");
            var prior=jdbc.queryForList("SELECT release_fingerprint,requester FROM ops_skill_run_pin WHERE run_id=?",runId);
            if(prior.isEmpty())jdbc.update("INSERT INTO ops_skill_run_pin(run_id,release_fingerprint,requester) VALUES(?,?,?)",runId,active.fingerprint(),requester);
            else {
                var existing=prior.get(0);
                if(!requester.equals(existing.get("requester")))throw new IllegalStateException("Run pin belongs to another requester");
                String fingerprint=(String)existing.get("release_fingerprint");
                var payload=jdbc.queryForObject("SELECT payload FROM ops_skill_release WHERE fingerprint=?",String.class,fingerprint);
                return new Active(fingerprint,null,0,payload);
            }
            return active;});
    }
    public List<Map<String,Object>> history(int limit) {
        return jdbc.queryForList("SELECT from_fingerprint,to_fingerprint,action,actor,reason,generation,created_at FROM ops_skill_activation ORDER BY id DESC LIMIT ?",Math.max(1,Math.min(100,limit)));
    }
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Skill artifact serialization failed",e);}}
    private static void validateActor(String actor,String reason){if(actor==null||actor.isBlank()||actor.length()>64||reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("Actor and release reason are required");}
}
