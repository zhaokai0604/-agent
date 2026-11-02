package com.award.log.config;

import com.award.log.analyzer.*;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.LogAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ResourceLifecycleTest {
    @Test @Timeout(15)
    void contextCloseInterruptsAllActivePoolsAndCancelsQueuedFutures() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ThreadPoolConfig.class)) {
            List<ThreadPoolTaskExecutor> executors = List.of(
                    context.getBean("sseTaskExecutor", ThreadPoolTaskExecutor.class),
                    context.getBean("logAnalysisExecutor", ThreadPoolTaskExecutor.class),
                    context.getBean("logAnalysisShardExecutor", ThreadPoolTaskExecutor.class));
            CountDownLatch started = new CountDownLatch(executors.size());
            CountDownLatch interrupted = new CountDownLatch(executors.size());
            CountDownLatch blocker = new CountDownLatch(1);
            List<Future<?>> queued = new java.util.ArrayList<>();
            for (var executor : executors) {
                assertTrue(executor.getCorePoolSize() <= executor.getMaxPoolSize());
                executor.setCorePoolSize(1);
                executor.execute(() -> {
                    started.countDown();
                    try { blocker.await(); }
                    catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                });
                queued.add(executor.submit(() -> fail("Queued work ran during shutdown")));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long before = System.nanoTime();
            context.close();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 5_000);
            for (int i = 0; i < executors.size(); i++) {
                assertTrue(executors.get(i).getThreadPoolExecutor().isTerminated());
                assertTrue(queued.get(i).isCancelled());
                var executor = executors.get(i);
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            }
        }
    }

    @Test @Timeout(15)
    void springInvokesAnalyzerLifecycleAndDestroyedAnalyzerCannotRestart() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LogAnalysisService.class, () -> mock(LogAnalysisService.class));
            context.registerBean(AiLogAlarmService.class, () -> mock(AiLogAlarmService.class));
            context.registerBean(LogCleaner.class, LogCleaner::new);
            context.registerBean(DrainParserFactory.class, () -> new DrainParserFactory(new DrainPlusParser(8, 50)));
            context.registerBean(RealTimeLogAnalyzer.class);
            context.refresh();
            var analyzer = context.getBean(RealTimeLogAnalyzer.class);
            analyzer.startRealTimeAnalysis();
            ExecutorService worker = (ExecutorService) ReflectionTestUtils.getField(analyzer, "workerPool");
            ExecutorService memory = (ExecutorService) ReflectionTestUtils.getField(analyzer, "memoryCheckScheduler");
            ExecutorService flush = (ExecutorService) ReflectionTestUtils.getField(analyzer, "analysisScheduler");
            assertNotNull(worker);
            assertNotNull(memory);
            assertNotNull(flush);
            context.close();
            assertTrue(worker.isTerminated());
            assertTrue(memory.isTerminated());
            assertTrue(flush.isTerminated());
            analyzer.init();
            analyzer.startRealTimeAnalysis();
            analyzer.resetAnalysisState();
            analyzer.addLog(Map.of("content", "late event"));
            analyzer.destroy();
            assertSame(worker, ReflectionTestUtils.getField(analyzer, "workerPool"));
            assertTrue(((List<?>) ReflectionTestUtils.getField(analyzer, "logBuffer")).isEmpty());
        }
    }
}
