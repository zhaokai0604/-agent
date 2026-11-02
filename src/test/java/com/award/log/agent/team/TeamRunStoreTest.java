package com.award.log.agent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class TeamFixture {
    JdbcTemplate jdbc;TeamDataPolicy data;TeamRunStore store;JdbcDataSource ds;
    @BeforeEach void database(){
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).load().migrate();jdbc=new JdbcTemplate(ds);data=new TeamDataPolicy(new ObjectMapper());
        store=new TeamRunStore(jdbc,new DataSourceTransactionManager(ds),data,180000,6);
    }
    TeamRunStore.Run create(String request){return store.create(UUID.randomUUID().toString(),7,request,"inspect fixture","fixture","release","{}","[]");}
    TeamRunStore.Run waiting(TeamRunStore.Run run){store.advance(run.id(),run.execution(),"PREVIEWING",Map.of("plan_json","{}","plan_hash","hash"));return store.pause(run.id(),run.execution(),"WAITING_APPROVAL",null);}
}
class TeamRunStoreTest extends TeamFixture {
    @Test void ownerRequestIdempotencyAndInstanceClaimsPreventDuplicateWorkers(){
        var run=create("one");assertEquals(run.id(),create("one").id());
        assertThrows(IllegalStateException.class,()->store.create("different",7,"one","changed","fixture","release","{}","[]"));
        assertTrue(store.claim(run));assertFalse(store.claim(run));
        var second=new TeamRunStore(jdbc,new DataSourceTransactionManager(ds),data,180000,6);
        assertFalse(second.claim(run));assertFalse(second.current(run));
        assertThrows(NoSuchElementException.class,()->store.owned(run.id(),8,false));
        assertEquals(run.id(),store.owned(run.id(),8,true).id());
    }
    @Test void exactRevisionAndPlanHashAreRequiredAndOldTokenIsFenced(){
        var initial=create("approval");var run=waiting(initial);
        assertThrows(IllegalStateException.class,()->store.approve(run.id(),run.revision()-1,"hash","7"));
        assertThrows(IllegalStateException.class,()->store.approve(run.id(),run.revision(),"changed","7"));
        var approved=store.approve(run.id(),run.revision(),"hash","7");assertNotEquals(initial.execution(),approved.execution());
        assertThrows(IllegalStateException.class,()->store.advance(initial.id(),initial.execution(),"EXECUTING",Map.of()));
        assertTrue(store.claim(approved));assertTrue(store.events(run.id()).stream().anyMatch(e->"HUMAN_APPROVED".equals(e.get("kind"))));
    }
    @Test void cancelAfterWriteRetainsLocksAndLateReceiptWithoutClaimingRollback(){
        var run=store.approve(waiting(create("write")).id(),3,"hash","7");
        store.acquire(run,Set.of("LOCAL_FILESYSTEM"));store.intent(run,0,Map.of("path","fixture"));
        var stopped=store.cancel(run.id(),"7");assertEquals("NEEDS_HUMAN",stopped.state());assertTrue(stopped.writeStarted());
        int eventCount=store.events(run.id()).size();
        for(int i=0;i<250;i++)assertEquals(stopped.revision(),store.cancel(run.id(),"7").revision());
        assertEquals(eventCount,store.events(run.id()).size());
        store.receipt(run,0,Map.of("success",true,"recoveryHandle","fixture-handle"));
        assertEquals("NEEDS_HUMAN",store.get(run.id()).state());
        var other=create("other");assertThrows(IllegalStateException.class,()->store.acquire(other,Set.of("LOCAL_FILESYSTEM")));
        assertThrows(IllegalStateException.class,()->store.edit(stopped.id(),stopped.revision(),"{}","retry","7",false));
        var edited=store.edit(stopped.id(),stopped.revision(),"{}","actual state reviewed","7",true);
        assertEquals("PREVIEWING",edited.state());store.acquire(other,Set.of("LOCAL_FILESYSTEM"));
        assertTrue(store.events(run.id()).stream().anyMatch(e->e.get("payload").toString().contains("fixture-handle")));
    }
    @Test void deadlineRequiresTakeoverAndCannotCommitCompleted(){
        var run=create("expired");jdbc.update("UPDATE ops_team_run SET deadline_at=? WHERE run_id=?",Timestamp.from(Instant.now().minusSeconds(1)),run.id());
        assertThrows(IllegalStateException.class,()->store.pause(run.id(),run.execution(),"COMPLETED",null));
        store.reconcileExpired();assertEquals("NEEDS_HUMAN",store.get(run.id()).state());
        assertFalse(store.current(run));
    }
    @Test void modelBudgetAndCredentialRedactionArePersistent(){
        var run=create("models");for(int i=0;i<6;i++)store.reserveModel(run,"DIAGNOSIS",Map.of("password","private-fixture-secret"));
        assertThrows(IllegalStateException.class,()->store.reserveModel(run,"PLANNER",Map.of()));
        assertEquals(6,store.get(run.id()).modelCalls());
        assertFalse(store.events(run.id()).toString().contains("private-fixture-secret"));
        assertTrue(store.events(run.id()).toString().contains("REDACTED"));
    }
    @Test @Timeout(10) void simultaneousRequestCreationReturnsOneRun()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return create("race");});var b=pool.submit(()->{gate.await();return create("race");});gate.countDown();assertEquals(a.get().id(),b.get().id());}
        finally{pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ops_team_run",Integer.class));
    }
}
