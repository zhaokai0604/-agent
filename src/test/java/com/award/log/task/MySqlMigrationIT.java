package com.award.log.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit compatibility run against a disposable local server, never the application datasource. */
class MySqlMigrationIT {
    @TempDir Path dir;

    @Test void nativeMySqlFreshLegacyRecoveryAndFencing() throws Exception {
        String server=System.getProperty("thresh.mysql-test.server", "");
        if (!server.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{4,5}/")) {
            throw new IllegalArgumentException("Pass an explicit disposable localhost server URL; production URLs are forbidden");
        }
        String user=System.getProperty("thresh.mysql-test.user","root");
        String password=System.getProperty("thresh.mysql-test.password","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(server,user,password));
        String datadir=admin.queryForObject("SELECT @@datadir",String.class);
        if (datadir == null || !datadir.matches(".*[\\\\/]threshcore-mysql-canary-[a-f0-9]{32}[\\\\/]?")) {
            throw new IllegalArgumentException("Server datadir must be a disposable threshcore-mysql-canary-UUID directory");
        }
        String fresh="threshcore_canary_"+UUID.randomUUID().toString().replace("-","");
        String legacy="threshcore_canary_"+UUID.randomUUID().toString().replace("-","");
        List<String> created=new ArrayList<>();
        try {
            for(String schema:List.of(fresh,legacy)) {
                assertTrue(schema.matches("threshcore_canary_[a-f0-9]{32}"));
                admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4"); created.add(schema);
            }
            var ds=new DriverManagerDataSource(server+fresh+"?serverTimezone=UTC",user,password);
            Flyway.configure().dataSource(ds).target("2").load().migrate();
            var beforeUpgrade=new JdbcTemplate(ds);
            beforeUpgrade.execute("ALTER TABLE alarm_rule MODIFY COLUMN rule_type VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL DEFAULT 'KEYWORD' COMMENT 'canary'");
            var f=Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).load();
            assertEquals(4,f.migrate().migrationsExecuted); assertEquals(0,f.migrate().migrationsExecuted); f.validate();
            var jdbc=new JdbcTemplate(ds);
            var column=jdbc.queryForMap("SELECT CHARACTER_MAXIMUM_LENGTH,COLLATION_NAME,IS_NULLABLE,COLUMN_DEFAULT,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alarm_rule' AND column_name='rule_type'");
            assertEquals(32L,((Number)column.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
            assertEquals("utf8mb4_bin",column.get("COLLATION_NAME"));assertEquals("YES",column.get("IS_NULLABLE"));
            assertEquals("KEYWORD",column.get("COLUMN_DEFAULT"));assertEquals("canary",column.get("COLUMN_COMMENT"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class));
            var releases=new com.award.log.agent.skill.SkillReleaseRegistry(jdbc,new DataSourceTransactionManager(ds),new com.award.log.agent.skill.SkillReleaseEvaluator());
            var skills=new com.award.log.agent.skill.SkillCatalogLoader(releases);
            String base=releases.current().fingerprint();
            var candidate=new TreeMap<String,com.award.log.agent.skill.SkillCatalogLoader.YamlSkillDefinition>();skills.all().forEach(d->candidate.put(d.id(),d));
            var cpu=candidate.get("cpu-pressure");
            candidate.put(cpu.id(),new com.award.log.agent.skill.SkillCatalogLoader.YamlSkillDefinition(cpu.id(),"native release fixture",cpu.playbook(),cpu.tools(),cpu.stopConditions(),cpu.redLines(),cpu.minIndependentSignals(),"1.1.0",cpu.schemaVersion(),cpu.runtimeContract()));
            try(var pinned=skills.pinRun("mysql-run","TEST")) {
                releases.publish(base,candidate,"TEST","native compatibility");
                assertEquals("1.0.0",skills.find("cpu-pressure").version());
            }
            var upgraded=releases.current();
            releases.rollback(upgraded.fingerprint(),base,"TEST","native rollback");
            assertEquals(base,new com.award.log.agent.skill.SkillCatalogLoader(releases).status().get("catalogFingerprint"));
            var first=new DurableLogTaskStore(jdbc,new DataSourceTransactionManager(ds),dir.toString(),30);
            var teamData=new com.award.log.agent.team.TeamDataPolicy(new com.fasterxml.jackson.databind.ObjectMapper());
            var teams=new com.award.log.agent.team.TeamRunStore(jdbc,new DataSourceTransactionManager(ds),teamData,180000,6);
            var team=teams.create("native-team",7,"native-request","native canary","fixture","fp","{}","[]");
            assertTrue(teams.claim(team));assertFalse(teams.claim(team));
            teams.advance(team.id(),team.execution(),"PREVIEWING",Map.of("plan_json","{}","plan_hash","hash"));
            var waiting=teams.pause(team.id(),team.execution(),"WAITING_APPROVAL",null);
            var teamExecution=teams.approve(team.id(),waiting.revision(),"hash","7");
            teams.acquire(teamExecution,Set.of("LOCAL_FILESYSTEM"));teams.intent(teamExecution,0,Map.of("canary",true));
            var stopped=teams.cancel(team.id(),"7");assertTrue(stopped.writeStarted());assertEquals("NEEDS_HUMAN",stopped.state());
            teams.receipt(teamExecution,0,Map.of("success",true,"recover","native-canary-handle"));
            assertThrows(IllegalStateException.class,()->teams.edit(team.id(),stopped.revision(),"{}","retry","7",false));
            teams.edit(team.id(),stopped.revision(),"{}","actual state reviewed","7",true);
            assertTrue(teams.events(team.id()).stream().anyMatch(e->e.get("payload").toString().contains("native-canary-handle")));
            jdbc.update("INSERT INTO log_analysis_task(task_id,status) VALUES('native-ai','COMPLETED')");
            jdbc.update("INSERT INTO log_task_runtime(task_id,phase,runtime_status) VALUES('native-ai','COMPLETED','COMPLETED')");
            var diagnosis=new LogDiagnosisJobStore(jdbc,first,3,120);
            var initial=diagnosis.begin("native-ai","first");assertTrue(diagnosis.claim(initial));
            diagnosis.cancel("native-ai",initial.runId());
            var retried=diagnosis.begin("native-ai","retry");assertTrue(diagnosis.claim(retried));
            assertFalse(diagnosis.finish(initial,"SUCCEEDED",null,"late model output"));
            assertTrue(diagnosis.finish(retried,"SUCCEEDED",null,"依据样本定位连接拒绝，仍需复核服务状态。"));
            assertEquals("依据样本定位连接拒绝，仍需复核服务状态。",diagnosis.status("native-ai").diagnosis());
            assertEquals("SUCCEEDED",first.snapshot("native-ai").aiStatus());
            jdbc.update("INSERT INTO log_analysis_task(task_id,status) VALUES('native','PENDING')");
            Path upload=Files.writeString(dir.resolve("native-upload.log"),"INFO native mysql\n");
            first.stage("native",upload.toFile()); String old=first.claim("native");
            try(var ignored=TaskExecutionContext.open("native",old)) {
                assertEquals("INFO native mysql\n",Files.readString(first.retainedInputForExecution("native").toPath()));
            }
            jdbc.update("UPDATE log_analysis_task SET status='PROCESSING' WHERE task_id='native'");
            jdbc.update("UPDATE log_task_runtime SET lease_until=? WHERE task_id='native'",Timestamp.from(Instant.now().minusSeconds(1)));
            var second=new DurableLogTaskStore(jdbc,new DataSourceTransactionManager(ds),dir.toString(),30);
            second.reconcileExpired(); assertTrue(second.needsRecovery("native"));
            Path retry=second.prepareRecovery("native").toPath();
            try {
                assertEquals("INFO native mysql\n",Files.readString(retry));
                String execution=second.claim("native"); assertNotEquals(old,execution);
                try(var ignored=TaskExecutionContext.open("native",old)) {
                    assertFalse(second.locked("native",()->second.currentExecution("native")));
                    first.recoveryRequired("native","stale execution");
                }
                first.close(); assertEquals("RUNNING",second.snapshot("native").status());
            } finally {Files.deleteIfExists(retry);}
            var oldDs=new DriverManagerDataSource(server+legacy,user,password);
            var oldJdbc=new JdbcTemplate(oldDs);
            oldJdbc.execute("CREATE TABLE log_analysis_task(task_id VARCHAR(64) PRIMARY KEY,status VARCHAR(32))");
            oldJdbc.update("INSERT INTO log_analysis_task VALUES('old','COMPLETED')");
            assertThrows(FlywayException.class,()->Flyway.configure().dataSource(oldDs).load().migrate());
            var adopted=Flyway.configure().dataSource(oldDs).baselineOnMigrate(true).baselineVersion("0").load();
            assertEquals(6,adopted.migrate().migrationsExecuted);
            assertEquals("COMPLETED",oldJdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='old'",String.class));
            oldJdbc.update("INSERT INTO alarm_rule(name,rule_type,rule_expression,severity,push_channels,enabled) VALUES('private','KEYWORD','private','ERROR','BOTH',1)");
            adopted.migrate(); assertEquals(1,oldJdbc.queryForObject("SELECT COUNT(*) FROM alarm_rule WHERE name='private'",Integer.class));
        } finally {
            for(String schema:created) admin.execute("DROP DATABASE "+schema);
        }
    }
}
