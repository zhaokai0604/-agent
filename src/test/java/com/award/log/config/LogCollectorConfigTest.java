package com.award.log.config;

import com.award.log.collector.LogCollectorManager;
import com.award.log.collector.impl.NetworkLogCollector;
import com.award.log.collector.impl.NetworkLogCollectorV2;
import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LogCollectorConfigTest {

    @TempDir java.nio.file.Path temp;

    @Test void contextCloseStopsCollectorAndReleasesCheckpointOwnership() throws Exception {
        var source = java.nio.file.Files.writeString(temp.resolve("lifecycle.log"), "retained\n");
        var checkpoints = temp.resolve("checkpoints");
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        try {
            org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "log.collector.enabled=true", "log.collector.file.path=" + source,
                    "log.collector.file.checkpoint-directory=" + checkpoints,
                    "log.pipeline.collector.start-at-eof=false", "log.collector.network.enabled=false",
                    "log.collector.db.enabled=false");
            context.registerBean(DataSource.class, () -> mock(DataSource.class));
            context.registerBean(SecuritySignalService.class, () -> mock(SecuritySignalService.class));
            context.register(LogCollectorConfig.class); context.refresh();
            var manager = context.getBean(LogCollectorManager.class);
            assertTrue(manager.getCollector("file-collector").isRunning());
            context.close();
            assertFalse(manager.getCollector("file-collector").isRunning());
            var reopened = new com.award.log.collector.impl.FileLogCollector(source.toString(), "reopened", ".log", "",
                    2, 1000, 100, true, checkpoints);
            reopened.stop();
        } finally { context.close(); }
    }

    @Test
    void udpCollectorUsesNetworkLogCollectorV2() {
        LogCollectorConfig config = new LogCollectorConfig(
                mock(KafkaLogProducer.class),
                mock(DataSource.class),
                mock(SecuritySignalService.class));
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "filePath", "missing-path-for-test");
        ReflectionTestUtils.setField(config, "networkEnabled", true);
        ReflectionTestUtils.setField(config, "networkProtocol", "UDP");
        ReflectionTestUtils.setField(config, "networkPort", 0);
        ReflectionTestUtils.setField(config, "dbEnabled", false);

        LogCollectorManager manager = config.logCollectorManager();

        assertNotNull(manager.getCollector("NetworkLogCollectorV2"));
        assertInstanceOf(NetworkLogCollectorV2.class, manager.getCollector("NetworkLogCollectorV2"));
        manager.stopAll();
    }

    @Test
    void tcpCollectorKeepsLegacyNetworkCollector() {
        LogCollectorConfig config = new LogCollectorConfig(
                null,
                mock(DataSource.class),
                mock(SecuritySignalService.class));
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "filePath", "missing-path-for-test");
        ReflectionTestUtils.setField(config, "networkEnabled", true);
        ReflectionTestUtils.setField(config, "networkProtocol", "TCP");
        ReflectionTestUtils.setField(config, "networkPort", 0);
        ReflectionTestUtils.setField(config, "dbEnabled", false);

        LogCollectorManager manager = config.logCollectorManager();

        assertNotNull(manager.getCollector("network-collector"));
        assertInstanceOf(NetworkLogCollector.class, manager.getCollector("network-collector"));
        manager.stopAll();
    }
}
