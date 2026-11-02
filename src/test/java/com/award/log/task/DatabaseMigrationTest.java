package com.award.log.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTest {
    JdbcDataSource source() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");return ds;
    }
    Flyway flyway(JdbcDataSource ds,boolean adopt) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(adopt).baselineVersion("0").cleanDisabled(true).load();
    }
    @Test void freshMigrationIsRepeatableAndHasNoSeedAccounts() {
        var ds=source();var f=flyway(ds,false);assertEquals(7,f.migrate().migrationsExecuted);
        assertEquals(0,f.migrate().migrationsExecuted);f.validate();
        var jdbc=new JdbcTemplate(ds);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alarm_rule",Integer.class));
        assertThrows(FlywayException.class,f::clean);
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='2'");
        assertThrows(FlywayException.class,f::validate);
    }
    @Test void legacyAdoptionMustBeExplicitAndPreservesExistingRules() {
        var ds=source();var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE log_analysis_task(task_id VARCHAR(64) PRIMARY KEY,status VARCHAR(32))");
        jdbc.update("INSERT INTO log_analysis_task VALUES('old','COMPLETED')");
        assertThrows(FlywayException.class,()->flyway(ds,false).migrate());
        var f=flyway(ds,true);assertEquals(7,f.migrate().migrationsExecuted);
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM log_analysis_task WHERE task_id='old'",String.class));
        jdbc.update("INSERT INTO alarm_rule(name,rule_type,rule_expression,severity,push_channels,enabled) VALUES('custom','KEYWORD','private','ERROR','BOTH',1)");
        f.migrate();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alarm_rule WHERE name='custom'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM log_analysis_task WHERE user_id IS NOT NULL",Integer.class));
    }

    @Test void upgradingV2PreservesRulesAndWidensColumnsWithoutChangingValues() {
        var ds=source();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("2").load().migrate();
        jdbc.update("INSERT INTO alarm_rule(name,rule_type,rule_expression,severity,push_channels,enabled) VALUES('private','KEYWORD','private','ERROR','EMAIL',1)");
        jdbc.update("INSERT INTO ops_workflow_memory(workflow_id,domain_tag,title,steps_json) VALUES('private-wf','disk','private','[]')");
        var f=flyway(ds,false);assertEquals(5,f.migrate().migrationsExecuted);
        assertEquals("KEYWORD",jdbc.queryForObject("SELECT rule_type FROM alarm_rule WHERE name='private'",String.class));
        assertEquals("EMAIL",jdbc.queryForObject("SELECT push_channels FROM alarm_rule WHERE name='private'",String.class));
        assertEquals("private",jdbc.queryForObject("SELECT title FROM ops_workflow_memory WHERE workflow_id='private-wf'",String.class));
        assertEquals(32,jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_name='alarm_rule' AND column_name='rule_type'",Integer.class));
        jdbc.update("INSERT INTO ops_audit_trace(trace_id,channel,audit_kind,target_host_id,parent_trace_id,confirmation_id) VALUES('proof','TEST','EXECUTION','host','parent','approval')");
        assertEquals("approval",jdbc.queryForObject("SELECT confirmation_id FROM ops_audit_trace WHERE trace_id='proof'",String.class));
        assertEquals(0,f.migrate().migrationsExecuted);
    }

    @Test void duplicateLegacyEmailsBlockUpgradeWithoutDeletingAccounts() {
        var ds=source();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("2").load().migrate();
        jdbc.execute("ALTER TABLE sys_user DROP CONSTRAINT uk_email");
        jdbc.update("INSERT INTO sys_user(username,password,email) VALUES('first','test','duplicate@test.invalid'),('second','test','duplicate@test.invalid')");
        assertThrows(FlywayException.class,()->flyway(ds,false).migrate());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE email='duplicate@test.invalid'",Integer.class));
    }

    @Test void incompatibleColumnBlocksUpgradeInsteadOfHidingSchemaFailure() {
        var ds=source();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("2").load().migrate();
        jdbc.execute("ALTER TABLE ops_audit_trace ALTER COLUMN duration_ms SET DATA TYPE VARCHAR(32)");
        jdbc.update("INSERT INTO ops_audit_trace(trace_id,channel,duration_ms) VALUES('old-trace','TEST','legacy')");
        assertThrows(FlywayException.class,()->flyway(ds,false).migrate());
        assertEquals("legacy",jdbc.queryForObject("SELECT duration_ms FROM ops_audit_trace WHERE trace_id='old-trace'",String.class));
    }

    @Test void diagnosisUpgradePreservesOldTextWithoutClaimingItSucceeded() {
        var ds=source();var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("4").load().migrate();
        jdbc.update("INSERT INTO log_analysis_task(task_id,file_name,status,ai_diagnosis) VALUES('legacy-ai','old.log','COMPLETED','old service error text')");
        jdbc.update("INSERT INTO log_task_runtime(task_id,phase,runtime_status) VALUES('legacy-ai','COMPLETED','COMPLETED')");
        assertEquals(3,flyway(ds,false).migrate().migrationsExecuted);
        assertEquals("UNTRACKED",jdbc.queryForObject("SELECT ai_status FROM log_task_runtime WHERE task_id='legacy-ai'",String.class));
        assertEquals("old service error text",jdbc.queryForObject("SELECT ai_diagnosis FROM log_analysis_task WHERE task_id='legacy-ai'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM log_diagnosis_job",Integer.class));
    }
}
