package com.award.log.agent.skill;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillReleaseRegistryTest {
    JdbcTemplate jdbc;
    JdbcDataSource ds;
    SkillReleaseRegistry registry;
    SkillCatalogLoader loader;
    @BeforeEach void init(){
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(ds).load().migrate();jdbc=new JdbcTemplate(ds);
        registry=registry();loader=new SkillCatalogLoader(registry);
    }
    SkillReleaseRegistry registry(){return new SkillReleaseRegistry(jdbc,new DataSourceTransactionManager(ds),new SkillReleaseEvaluator());}
    Map<String,SkillCatalogLoader.YamlSkillDefinition> definitions(){var m=new TreeMap<String,SkillCatalogLoader.YamlSkillDefinition>();loader.all().forEach(d->m.put(d.id(),d));return m;}
    Map<String,SkillCatalogLoader.YamlSkillDefinition> changed(String version,String description){
        var m=definitions();var d=m.get("cpu-pressure");m.put(d.id(),new SkillCatalogLoader.YamlSkillDefinition(d.id(),description,d.playbook(),d.tools(),d.stopConditions(),d.redLines(),d.minIndependentSignals(),version,d.schemaVersion(),d.runtimeContract()));return m;
    }
    @Test void publishedCatalogAndRollbackSurviveNewLoaderWithOlderDeploymentFiles(){
        String original=registry.current().fingerprint();
        var upgraded=registry.publish(original,changed("1.1.0","new CPU diagnosis"),"7","reviewed fixtures");
        assertEquals("1.1.0",new SkillCatalogLoader(registry()).find("cpu-pressure").version());
        var restored=registry.rollback(upgraded.fingerprint(),original,"7","regression found");
        assertEquals(original,restored.fingerprint());assertEquals(3,restored.generation());
        assertEquals("1.0.0",new SkillCatalogLoader(registry()).find("cpu-pressure").version());
        assertEquals(3,registry.history(1000).size());
        assertEquals("ROLLBACK",registry.history(1).get(0).get("action"));
    }
    @Test void pinsKeepOriginalVersionAcrossPublishAndRestart(){
        String original=registry.current().fingerprint();
        var candidate=changed("1.1.0","upgraded");
        try(var fixed=loader.pinRun("run-1","7")){
            registry.publish(original,candidate,"7","approved");
            assertEquals("1.0.0",loader.find("cpu-pressure").version());
        }
        assertEquals("1.1.0",loader.find("cpu-pressure").version());
        var restarted=new SkillCatalogLoader(registry());
        try(var fixed=restarted.pinRun("run-1","7")){assertEquals("1.0.0",restarted.find("cpu-pressure").version());}
        assertThrows(IllegalStateException.class,()->registry.pin("run-1","8"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ops_skill_run_pin WHERE run_id='run-1'",Integer.class));
    }
    @Test void sameVersionDriftStaysRejectedAfterRollback(){
        String original=registry.current().fingerprint();
        var published=registry.publish(original,changed("1.1.0","first content"),"7","approved");
        registry.rollback(published.fingerprint(),original,"7","restore");
        var drift=changed("1.1.0","different content");
        assertEquals(false,registry.evaluate(drift).get("passed"));
        assertThrows(IllegalStateException.class,()->registry.publish(original,drift,"7","attempt drift"));
        assertEquals(original,registry.current().fingerprint());
        jdbc.update("UPDATE ops_skill_release SET payload='{}' WHERE fingerprint=?",published.fingerprint());
        assertThrows(IllegalStateException.class,()->registry.publish(original,changed("1.1.0","first content"),"7","retry stored version"));
        assertEquals(original,registry.current().fingerprint());
    }
    @Test void failedFixturesAndMalformedArtifactCannotChangeReleaseHead(){
        var original=registry.current();var bad=new TreeMap<>(changed("1.1.0","bad"));var d=bad.get("cpu-pressure");
        bad.put(d.id(),new SkillCatalogLoader.YamlSkillDefinition(d.id(),d.description(),d.playbook(),List.of("ProcessTool"),d.stopConditions(),d.redLines(),d.minIndependentSignals(),d.version(),d.schemaVersion(),d.runtimeContract()));
        assertEquals(false,registry.evaluate(bad).get("passed"));
        assertThrows(IllegalStateException.class,()->registry.publish(original.fingerprint(),bad,"7","fixture failure"));
        assertThrows(IllegalArgumentException.class,()->registry.publish(original.fingerprint(),definitions(),"7",""));
        assertEquals(original,registry.current());assertEquals(1,registry.history(100).size());
        jdbc.update("UPDATE ops_skill_release SET payload='{}' WHERE fingerprint=?",original.fingerprint());
        assertThrows(IllegalStateException.class,()->new SkillCatalogLoader(registry()));
    }
    @Test @Timeout(15) void onlyOneConcurrentPublisherWinsExpectedFingerprint() throws Exception {
        String original=registry.current().fingerprint();var a=changed("1.1.0","candidate a");var b=changed("1.1.0","candidate b");
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            List<Future<Boolean>> futures=new ArrayList<>();
            for(var candidate:List.of(a,b))futures.add(pool.submit(()->{start.await();try{registry().publish(original,candidate,"7","approved");return true;}catch(IllegalStateException race){return false;}}));
            start.countDown();assertEquals(1,(futures.get(0).get()?1:0)+(futures.get(1).get()?1:0));
            assertEquals(2,registry.history(100).size());assertEquals(2,registry.current().generation());
        }finally{pool.shutdownNow();}
    }
    @Test void previewBindsDeploymentFingerprintAndDoesNotActivateImplicitly(){
        String original=registry.current().fingerprint();
        Map<String,Object> preview=loader.previewDeployed();
        assertEquals(true,((Map<?,?>)preview.get("evaluation")).get("passed"));
        assertEquals(73,((Map<?,?>)preview.get("evaluation")).get("checks"));
        assertThrows(IllegalStateException.class,()->loader.publish(original,"tampered","7","approved"));
        loader.publish(original,(String)preview.get("candidateFingerprint"),"7","unchanged deployment");
        assertEquals(1,registry.history(100).size());
        assertThrows(IllegalStateException.class,()->loader.rollback(original));
    }
}
