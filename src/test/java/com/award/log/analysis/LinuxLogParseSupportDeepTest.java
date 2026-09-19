package com.award.log.analysis;

import com.award.log.model.LogProtocolType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinuxLogParseSupportDeepTest {

    @Test
    void parsesSyslogJournalAuditRfcAndContainerEdgeCases() {
        assertEquals(30, LinuxLogParseSupport.parseSyslogPriNumeric("<30>message"));
        assertNull(LinuxLogParseSupport.parseSyslogPriNumeric("<999>message"));
        assertNull(LinuxLogParseSupport.parseSyslogPriNumeric("message"));
        assertEquals(3, LinuxLogParseSupport.syslogFacilityCode(30));
        assertEquals("message", LinuxLogParseSupport.stripLeadingSyslogPri("<30>message"));
        assertEquals("", LinuxLogParseSupport.stripLeadingSyslogPri(null));

        assertNotNull(LinuxLogParseSupport.tryRfc5424Timestamp("<34>1 2024-01-01T10:00:00Z host app - - msg"));
        assertNull(LinuxLogParseSupport.tryRfc5424Timestamp("<34>1 - host app - - msg"));
        assertNotNull(LinuxLogParseSupport.tryJournalJsonRealtime("{\"__REALTIME_TIMESTAMP\": \"1700000000000000\"}"));
        assertNull(LinuxLogParseSupport.tryJournalJsonRealtime("{\"__REALTIME_TIMESTAMP\": \"0\"}"));
        assertNotNull(LinuxLogParseSupport.tryAuditMsgTimestamp("type=USER msg=audit(1700000000.123456789012:42): ok"));
        assertNull(LinuxLogParseSupport.tryAuditMsgTimestamp("msg=audit(bad.1:1)"));
    }

    @Test
    void parsesDockerCriTagsUnitsKernelHintsAndFeatureMaps() {
        String docker = "{\"log\":\"x\",\"stream\":\"stdout\",\"timestamp\":\"2024-01-01T10:00:00Z\"}";
        String cri = "2024-01-01T10:00:00Z stderr F message";
        assertNotNull(LinuxLogParseSupport.tryContainerDockerJsonTimestamp(docker));
        assertTrue(LinuxLogParseSupport.isDockerJsonFileLine(docker));
        assertFalse(LinuxLogParseSupport.isDockerJsonFileLine("short"));
        assertNotNull(LinuxLogParseSupport.tryCriKubernetesTimestamp(cri));
        assertTrue(LinuxLogParseSupport.isCriKubernetesLine(cri));
        assertEquals("1234", LinuxLogParseSupport.extractTagPid("sshd[1234]: accepted"));
        assertEquals("sshd", LinuxLogParseSupport.extractTagName("sshd[1234]: accepted"));
        assertEquals("nginx.service", LinuxLogParseSupport.extractSystemdUnit("UNIT=nginx.service"));
        assertTrue(LinuxLogParseSupport.looksLikeKernelBootRelative("[  12.34] kernel: boot"));
        assertTrue(LinuxLogParseSupport.containsLinuxServiceHint("systemd[1]: Starting nginx.service"));
        assertFalse(LinuxLogParseSupport.containsLinuxServiceHint(null));

        Map<String, Object> features = new LinkedHashMap<>();
        LinuxLogParseSupport.enrichContainerHints(features, cri);
        LinuxLogParseSupport.enrichFeatureMap(features,
                "<30>sshd[1234]: UNIT=sshd.service [ 12.3] kernel: warning",
                LogProtocolType.LINUX_SYSTEM_LOG);
        assertEquals("cri", features.get("containerFormat"));
        assertEquals("err", features.get("criStream"));
        assertEquals(30, features.get("syslogPri"));
        assertEquals("1234", features.get("linuxTagPid"));
        assertEquals("sshd", features.get("linuxTag"));
        assertEquals("sshd.service", features.get("systemdUnit"));
        assertTrue(features.containsKey("kernelBootOffsetSec"));
        assertTrue(LinuxLogParseSupport.formatParsedExtrasSummary(features).contains("PRI:30"));
        assertEquals("", LinuxLogParseSupport.formatParsedExtrasSummary(Map.of()));
        LinuxLogParseSupport.enrichFeatureMap(null, "line", LogProtocolType.LINUX_SYSTEM_LOG);
        LinuxLogParseSupport.enrichFeatureMap(new LinkedHashMap<>(), "line", LogProtocolType.APPLICATION_LOG);
    }
}
