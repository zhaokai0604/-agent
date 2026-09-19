package com.award.log.collector.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActuatorLogCollectorCoverageTest {

    @Test
    void pollsHealthMetricsAndEnvAndCanDrainBuffer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/metrics", exchange -> respond(exchange, 200, "jvm.memory.used 12"));
        server.createContext("/env", exchange -> respond(exchange, 200, "{\"profiles\":[]}"));
        server.start();
        ActuatorLogCollector collector = new ActuatorLogCollector(
                "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            assertEquals("ActuatorLogCollector", collector.getName());
            assertFalse(collector.isRunning());
            assertTrue(collector.collect().isEmpty());
            collector.start();
            collector.start();
            List<String> logs = waitForLogs(collector);
            assertEquals(3, logs.size());
            assertTrue(logs.get(0).contains("ACTUATOR-HEALTH"));
            assertTrue(logs.stream().anyMatch(s -> s.contains("ACTUATOR-METRICS")));
            assertTrue(logs.stream().anyMatch(s -> s.contains("ACTUATOR-ENV")));
            assertTrue(collector.collect().isEmpty());
        } finally {
            collector.stop();
            collector.stop();
            server.stop(0);
        }
    }

    private static List<String> waitForLogs(ActuatorLogCollector collector) throws Exception {
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.addAll(collector.collect());
            if (all.size() >= 3) {
                return all;
            }
            Thread.sleep(20);
        }
        all.addAll(collector.collect());
        return all;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
