package com.award.log.collector.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkLogCollectorCoverageTest {

    @Test
    void tcpCollectorAcceptsAndDrainsLines() throws Exception {
        NetworkLogCollector collector = new NetworkLogCollector(0, "tcp", "tcp-test");
        collector.start();
        try {
            ServerSocket server = awaitField(collector, "tcpServerSocket", ServerSocket.class);
            assertTrue(server.isBound());
            Socket socket = org.mockito.Mockito.mock(Socket.class);
            org.mockito.Mockito.when(socket.getInputStream()).thenReturn(
                    new ByteArrayInputStream("ERROR tcp line\nINFO second line\n".getBytes(StandardCharsets.UTF_8)));
            ReflectionTestUtils.invokeMethod(collector, "handleTcpClient", socket);
            List<String> logs = waitForLogs(collector, 2);
            assertTrue(logs.contains("ERROR tcp line"), logs.toString());
            assertTrue(logs.contains("INFO second line"), logs.toString());
            assertEquals("tcp-test", collector.getName());
            assertTrue(collector.isRunning());
        } finally {
            collector.stop();
            assertFalse(collector.isRunning());
        }
    }

    @Test
    void udpCollectorAcceptsDatagramsAndUnsupportedProtocolStops() throws Exception {
        NetworkLogCollector collector = new NetworkLogCollector(0, "udp", "udp-test");
        collector.start();
        try {
            DatagramSocket server = awaitField(collector, "udpServerSocket", DatagramSocket.class);
            byte[] data = "WARN udp line".getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket client = new DatagramSocket()) {
                client.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), server.getLocalPort()));
            }
            assertTrue(waitForLogs(collector, 1).contains("WARN udp line"));
        } finally {
            collector.stop();
        }

        NetworkLogCollector unsupported = new NetworkLogCollector(0, "http", "unsupported");
        unsupported.start();
        assertFalse(unsupported.isRunning());
    }

    private static <T> T awaitField(Object target, String name, Class<T> type) throws Exception {
        for (int i = 0; i < 100; i++) {
            Object value = ReflectionTestUtils.getField(target, name);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            Thread.sleep(20);
        }
        fail("collector did not open socket: " + name);
        return null;
    }

    private static List<String> waitForLogs(NetworkLogCollector collector, int expected) throws Exception {
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.addAll(collector.collect());
            if (all.size() >= expected) {
                return all;
            }
            Thread.sleep(20);
        }
        all.addAll(collector.collect());
        return all;
    }
}
