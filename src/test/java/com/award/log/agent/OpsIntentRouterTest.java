package com.award.log.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(OpsIntentRouter.Playbook.FULL_AUTONOMOUS, router.resolve("有 systemd 服务失败了"));
        assertEquals(OpsIntentRouter.Playbook.FULL_AUTONOMOUS, router.resolve("服务挂了"));
    }

    @Test
    void diskTakesPriorityOverCpuWhenBothMentioned() {
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, router.resolve("CPU高但主要是磁盘满了"));
    }

    @Test
    void shouldOrchestrate_forDemoPaths() {
        assertTrue(router.shouldOrchestrate("磁盘满了"));
        assertTrue(router.shouldOrchestrate("一键巡检"));
    }
}
