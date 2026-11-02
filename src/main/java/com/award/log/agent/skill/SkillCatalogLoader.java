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

/**
 * 从 classpath:skills/*.yaml 加载结构化运维 Skill。
 */
@Component
public class SkillCatalogLoader {

    private record Catalog(Map<String,YamlSkillDefinition> skills,String fingerprint,long generation) { }
    private volatile Catalog catalog=new Catalog(Map.of(),"",0);
    private Catalog previous;
    private final SkillReleaseRegistry registry;
    private final ThreadLocal<Catalog> pinned=new ThreadLocal<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();

    /** Standalone catalog for local rule evaluation; production uses the persistent constructor. */
    public SkillCatalogLoader() { registry=null; reload(); }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillCatalogLoader(SkillReleaseRegistry registry) {
        this.registry=java.util.Objects.requireNonNull(registry);
        var active=registry.current();
        if(active==null)active=registry.bootstrap(readDeployed());
        apply(active);
    }

    private static Map<String,YamlSkillDefinition> readDeployed() {
        Map<String,YamlSkillDefinition> next=new java.util.TreeMap<>();
        try {
            var resources=new PathMatchingResourcePatternResolver().getResources("classpath:skills/*.yaml");
            if(resources.length==0||resources.length>128)throw new IllegalArgumentException("Skill 目录为空或超过上限");
            var options=new org.yaml.snakeyaml.LoaderOptions();
            options.setAllowDuplicateKeys(false); options.setCodePointLimit(65536);
            var yaml=new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options));
            int total=0;
            for(Resource resource:resources)try(InputStream in=resource.getInputStream()) {
                byte[] bytes=in.readNBytes(65537);total+=bytes.length;
                if(bytes.length>65536||total>2*1024*1024)throw new IllegalArgumentException("Skill 资源超过上限");
                Object loaded=yaml.load(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                if(!(loaded instanceof Map<?,?> map))throw new IllegalArgumentException("Skill 必须为 YAML 对象");
                var def=mapToDefinition(map);
                if(next.putIfAbsent(def.id(),def)!=null)throw new IllegalArgumentException("Skill 重复: "+def.id());
            }
            return Collections.unmodifiableMap(next);
        } catch(Exception e){throw new IllegalStateException("Skill 加载失败，保留上一份完整目录",e);}
    }

    public synchronized void reload() {
        if(registry!=null){apply(registry.current());return;}
        var next=readDeployed();
        for(var def:next.values()) {
            var old=catalog.skills().get(def.id()); if(old==null)continue;
            int order=compareVersion(def.version(),old.version());
            if(order<0||(order==0&&!def.fingerprint().equals(old.fingerprint())))throw new IllegalStateException("Skill 版本倒退或同版本内容漂移: "+def.id());
        }
        String fingerprint=fingerprint(next);
        if(!fingerprint.equals(catalog.fingerprint())) {
            previous=catalog;catalog=new Catalog(next,fingerprint,catalog.generation()+1);
        }
    }

    static String fingerprint(Map<String,YamlSkillDefinition> definitions) {
        var ordered=new java.util.TreeMap<String,String>();definitions.forEach((id,def)->ordered.put(id,def.fingerprint()));
        try{return com.award.log.security.effect.ConfirmTokenHmac.hashPreview(JSON.writeValueAsString(ordered));}
        catch(java.io.IOException e){throw new IllegalStateException("Skill catalog cannot be serialized",e);}
    }

    static Map<String,YamlSkillDefinition> decode(String payload) {
        try {
            if(payload==null||payload.length()>2*1024*1024)throw new IllegalArgumentException("Skill payload exceeds limit");
            Map<String,YamlSkillDefinition> values=JSON.readValue(payload,new com.fasterxml.jackson.core.type.TypeReference<java.util.TreeMap<String,YamlSkillDefinition>>(){});
            if(values.isEmpty()||values.size()>128)throw new IllegalArgumentException("Invalid Skill artifact size");
            for(var e:values.entrySet()) {
                var d=e.getValue();
                Map<String,Object> yaml=new LinkedHashMap<>();
                yaml.put("id",d.id());yaml.put("version",d.version());yaml.put("schema_version",d.schemaVersion());yaml.put("runtime_contract",d.runtimeContract());
                yaml.put("description",d.description());yaml.put("playbook",d.playbook());yaml.put("tools",d.tools());yaml.put("stop_conditions",d.stopConditions());yaml.put("red_lines",d.redLines());
                yaml.put("decision_rules",Map.of("confidence_gate",Map.of("min_independent_signals",d.minIndependentSignals())));
                if(!e.getKey().equals(d.id())||!d.equals(mapToDefinition(yaml)))throw new IllegalArgumentException("Invalid Skill artifact");
            }
            return Collections.unmodifiableMap(values);
        } catch(Exception e){throw new IllegalStateException("Stored Skill artifact failed validation",e);}
    }

    private synchronized void apply(SkillReleaseRegistry.Active active) {
        if(active==null)throw new IllegalStateException("Persistent Skill catalog unavailable");
        var definitions=decode(active.payload());
        if(!active.fingerprint().equals(fingerprint(definitions)))throw new IllegalStateException("Stored Skill catalog fingerprint changed");
        catalog=new Catalog(definitions,active.fingerprint(),active.generation());
    }
    private Catalog currentCatalog() {
        var fixed=pinned.get();if(fixed!=null)return fixed;
        if(registry!=null)reload();return catalog;
    }
    private Map<String,Object> status(Catalog current) {
        return Map.of("catalogFingerprint",current.fingerprint(),"generation",current.generation(),"skillCount",current.skills().size(),
                "schemaVersion",1,"runtimeContract","ops-pep/v1","durable",registry!=null);
    }
    public Map<String,Object> status(){return status(currentCatalog());}
    public Map<String,Object> catalogView(){var c=currentCatalog();return Map.of("skills",c.skills().values().stream().map(YamlSkillDefinition::toView).toList(),"catalog",status(c));}

    public synchronized void rollback(String expectedFingerprint) {
        if(registry!=null)throw new IllegalStateException("Persistent rollback requires actor and reason");
        if(previous==null||previous.skills().isEmpty()||!catalog.fingerprint().equals(expectedFingerprint))throw new IllegalStateException("无可回退版本或当前目录已变化");
        var old=catalog;catalog=new Catalog(previous.skills(),previous.fingerprint(),old.generation()+1);previous=old;
    }
    public Map<String,Object> previewDeployed() {
        var definitions=readDeployed();var current=currentCatalog();
        var changes=new ArrayList<Map<String,Object>>();
        for(var next:definitions.values()) {
            var old=current.skills().get(next.id());
            if(old==null||!old.fingerprint().equals(next.fingerprint()))changes.add(Map.of("skill",next.id(),"before",old==null?"":old.version(),"after",next.version()));
        }
        for(String id:current.skills().keySet())if(!definitions.containsKey(id))changes.add(Map.of("skill",id,"before",current.skills().get(id).version(),"after","REMOVED"));
        return Map.of("expectedFingerprint",current.fingerprint(),"candidateFingerprint",fingerprint(definitions),"changes",changes,
                "evaluation",registry==null?new SkillReleaseEvaluator().evaluate(definitions):registry.evaluate(definitions));
    }
    public synchronized void publish(String expected,String candidate,String actor,String reason) {
        if(registry==null)throw new IllegalStateException("Persistent release registry required");
        var definitions=readDeployed();
        if(!fingerprint(definitions).equals(candidate))throw new IllegalStateException("Deployment catalog changed; preview again");
        apply(registry.publish(expected,definitions,actor,reason));
    }
    public synchronized void rollback(String expected,String target,String actor,String reason) {
        if(registry==null)throw new IllegalStateException("Persistent release registry required");
        apply(registry.rollback(expected,target,actor,reason));
    }
    public List<Map<String,Object>> history(int limit){return registry==null?List.of():registry.history(limit);}
    public Scope pinRun(String runId,String requester) {
        Catalog fixed;
        if(registry==null)fixed=catalog;
        else {var active=registry.pin(runId,requester);var values=decode(active.payload());
            if(!active.fingerprint().equals(fingerprint(values)))throw new IllegalStateException("Pinned artifact fingerprint changed");
            fixed=new Catalog(values,active.fingerprint(),active.generation());}
        return new Scope(fixed);
    }
    public final class Scope implements AutoCloseable {
        private final Catalog before;
        private Scope(Catalog selected){before=pinned.get();pinned.set(selected);}
        @Override public void close(){if(before==null)pinned.remove();else pinned.set(before);}
    }
    static int compareVersion(String left,String right) {
        String[] a=left.split("\\."); String[] b=right.split("\\.");
        for(int i=0;i<3;i++) { int order=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i])); if(order!=0)return order; }
        return 0;
    }

    public List<YamlSkillDefinition> all() {
        return new ArrayList<>(currentCatalog().skills().values());
    }

    public YamlSkillDefinition find(String id) {
        return currentCatalog().skills().get(id);
    }

    public YamlSkillDefinition findByPlaybook(String playbook) {
        if (playbook == null) {
            return null;
        }
        String key = playbook.toLowerCase(Locale.ROOT).replace('_', '-');
        for (YamlSkillDefinition def : currentCatalog().skills().values()) {
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
        var allowed=java.util.Set.of("id","version","schema_version","runtime_contract","playbook","description","tools","decision_rules","stop_conditions","red_lines");
        if(map.keySet().stream().anyMatch(key->!allowed.contains(String.valueOf(key))))
            throw new IllegalArgumentException("Skill 包含未知契约字段");
        String version=str(map.get("version"));
        String schema=str(map.get("schema_version"));
        String runtime=str(map.get("runtime_contract"));
        if(!version.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
                || !"1".equals(schema) || !"ops-pep/v1".equals(runtime))
            throw new IllegalArgumentException("Skill 版本或运行时契约不兼容");
        String id = str(map.get("id"));
        String description = str(map.get("description"));
        String playbook = str(map.get("playbook"));
        List<String> tools = listOf(map.get("tools"));
        List<String> stopConditions = listOf(map.get("stop_conditions"));
        List<String> redLines = listOf(map.get("red_lines"));
        int minSignals = 2;
        Object rules = map.get("decision_rules");
        if(rules!=null && !(rules instanceof Map<?,?>))throw new IllegalArgumentException("Skill 决策规则必须为对象");
        if (rules instanceof Map<?, ?> rulesMap) {
            if(rulesMap.keySet().stream().anyMatch(key->!"confidence_gate".equals(key)))throw new IllegalArgumentException("未知 Skill 决策字段");
            Object gate = rulesMap.get("confidence_gate");
            if(gate!=null && !(gate instanceof Map<?,?>))throw new IllegalArgumentException("Skill 置信门控必须为对象");
            if (gate instanceof Map<?, ?> gateMap) {
                if(gateMap.keySet().stream().anyMatch(key->!"min_independent_signals".equals(key)))throw new IllegalArgumentException("未知 Skill 置信门控字段");
                Object min = gateMap.get("min_independent_signals");
                if (min instanceof Number n && n.doubleValue()==n.intValue() && n.intValue()>=1 && n.intValue()<=32) {
                    minSignals = n.intValue();
                } else if(min!=null) throw new IllegalArgumentException("Skill 独立信号数必须为 1-32 的整数");
            }
        }
        if(!id.matches("[a-z][a-z0-9-]{0,63}") || description.isBlank() || !playbook.matches("[a-z][a-z0-9-]{0,63}")
                || tools.isEmpty() || new java.util.HashSet<>(tools).size()!=tools.size()
                || tools.stream().anyMatch(tool->!com.award.log.mcp.McpKnownTools.isDispatchable(tool))
                || stopConditions.isEmpty() || !redLines.contains("no_write_without_pep"))
            throw new IllegalArgumentException("Skill 工具、红线或停止条件无效: "+id);
        return new YamlSkillDefinition(id, description, playbook, tools, stopConditions, redLines, minSignals,version,1,runtime);
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
            int minIndependentSignals,
            String version,int schemaVersion,String runtimeContract) {
        public YamlSkillDefinition {
            tools=List.copyOf(tools); stopConditions=List.copyOf(stopConditions); redLines=List.copyOf(redLines);
        }
        public String fingerprint() {
            try { return com.award.log.security.effect.ConfirmTokenHmac.hashPreview(JSON.writeValueAsString(new java.util.TreeMap<>(contract()))); }
            catch(java.io.IOException e) { throw new IllegalStateException("Skill 契约无法序列化",e); }
        }
        private Map<String,Object> contract() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("version",version); m.put("schemaVersion",schemaVersion); m.put("runtimeContract",runtimeContract);
            m.put("description", description);
            m.put("playbook", playbook);
            m.put("tools", tools);
            m.put("stopConditions", stopConditions);
            m.put("redLines", redLines);
            m.put("minIndependentSignals", minIndependentSignals);
            return m;
        }
        public Map<String,Object> toView() {
            var view=new LinkedHashMap<>(contract()); view.put("fingerprint",fingerprint()); return view;
        }
    }
}
