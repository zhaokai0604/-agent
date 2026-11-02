package com.award.log.task;

import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisTask;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskStateTransitionTest {
    @Test void cancellationAndCompletionHaveOnlyOneDatabaseWinner() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection=ds.getConnection(); var sql=connection.createStatement()) {
            sql.execute("create table log_analysis_task(task_id varchar(64) primary key, status varchar(32))");
            sql.execute("insert into log_analysis_task values('race','PROCESSING')");
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        String resource = "mapper-h2/LogAnalysisTaskMapper.xml";
        try (var in=getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Integer>> outcomes = new ArrayList<>();
            for (String state:List.of("CANCELLED","COMPLETED")) outcomes.add(pool.submit(() -> {
                start.await();
                try (SqlSession session=factory.openSession(true)) {
                    LogAnalysisTask task=new LogAnalysisTask(); task.setTaskId("race"); task.setStatus(state);
                    return session.getMapper(LogAnalysisTaskMapper.class).updateIfStatus(task,List.of("PROCESSING"));
                }
            }));
            start.countDown();
            assertEquals(1,outcomes.get(0).get()+outcomes.get(1).get());
            try (SqlSession session=factory.openSession(true)) {
                for (String state:List.of("PROCESSING","COMPLETED","FAILED","CANCELLED")) {
                    LogAnalysisTask task=new LogAnalysisTask(); task.setTaskId("race"); task.setStatus(state);
                    assertEquals(0, session.getMapper(LogAnalysisTaskMapper.class).updateIfStatus(task,List.of("PENDING","PROCESSING")));
                }
            }
        } finally { pool.shutdownNow(); }
    }
}
