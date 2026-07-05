package com.award.log.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsIntentRouterTest {

    private final OpsIntentRouter router = new OpsIntentRouter();

    @Test
    void demoDiskPhrases() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("磁盘满了"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("帮我预览清理临时文件"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("根分区使用率告警"));
    }

    @Test
    void demoCpuPhrases() {
        assertEquals(OpsIntentRouter.Playbook.CPU_PRESSURE, router.resolve("CPU占用过高怎么办"));
        assertEquals(OpsIntentRouter.Playbook.CPU_PRESSURE, router.resolve("系统负载过高"));
    }

    @Test
    void diskTakesPriorityOverAutonomousIntent() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("自动修复磁盘空间"));
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("一键自动清理临时文件"));
    }

    @Test
    void demoServiceRoutesAutonomous() {
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("有 systemd 服务失败了"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("服务挂了"));
    }

    @Test
    void generalHealthCheckRoutesAutonomous() {
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("帮我全面检查系统状态"));
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION, router.resolve("检查一下本机环境是否正常"));
        assertTrue(router.shouldOrchestrate("健康检查"));
    }

    @Test
    void diskTakesPriorityOverCpuWhenBothMentioned() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("CPU高但主要是磁盘满了"));
    }

    @Test
    void shouldOrchestrateForDemoPaths() {
        assertTrue(router.shouldOrchestrate("磁盘满了"));
        assertTrue(router.shouldOrchestrate("一键巡检"));
    }

    @Test
    void forceRemediateOnDirectCleanupPhrases() {
        assertTrue(router.forceRemediate("清理垃圾"));
        assertTrue(router.forceRemediate("帮我清理磁盘临时文件"));
        assertTrue(router.forceRemediate("删除 C:\\Users\\Administrator\\AppData\\Local\\Temp\\foo"));
        assertTrue(router.forceRemediate("磁盘满了清理临时"));
    }

    @Test
    void forceRemediateFalseWhenPreviewOnly() {
        assertFalse(router.forceRemediate("仅预览清理临时文件"));
        assertFalse(router.forceRemediate("帮我看看磁盘怎么样"));
    }
}
