package com.award.log.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 统一意图路由：决定走运维运行时编排还是 LLM 多步 Tool。
 * <p>规则优先于 LLM，保证磁盘/CPU/巡检等答辩高频路径输出稳定。</p>
 */
@Component
public class OpsIntentRouter {

    private static final Pattern AUTONOMOUS_INTENT = Pattern.compile(
            "(自动运维|全自动|一键运维|一键巡检|自主运维|智能运维|自动察觉|自动发现|自动处理|自动修复|"
                    + "察觉.*问题|发现.*解决|detect\\s*and\\s*fix|autonomous\\s*ops|auto\\s*remediat)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 失败服务 / systemd → 全栈自主编排（含 service AWM） */
    private static final Pattern SERVICE_FAILURE_INTENT = Pattern.compile(
            "(服务|systemd|单元|unit).*(失败|挂了|异常|down|failed|未运行|not\\s*running)|"
                    + "(failed\\s*unit|systemctl\\s*--failed|服务挂了|服务不可用)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PATROL_CONTINUE = Pattern.compile(
            "(继续处理|处理巡检|执行巡检|巡检待办|待确认方案|处理待办|继续处理巡检)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLEANUP_INTENT = Pattern.compile(
            "(清理|清除|删掉|删除|释放|修复).*(垃圾|空间|磁盘|日志|临时|tmp|temp|log)|"
                    + "(磁盘|空间|硬盘|根分区).*(满|不足|紧张|告警|满了)|"
                    + "(disk|space).*(full|low|cleanup)|"
                    + "cleanup.*(disk|log|temp)|"
                    + "(预览|查看|诊断).*(清理|清除).*(临时|tmp|temp|日志|磁盘)|"
                    + "磁盘满了|空间不够|清理临时",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CPU_INTENT = Pattern.compile(
            "(cpu|负载|卡顿|慢|性能|进程).*(高|满|异常|问题|过高|太多)|"
                    + "(高负载|cpu高|cpu满了|内存紧张|系统慢|负载过高|占用过高|CPU占用)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERAL_OPS_CHECK = Pattern.compile(
            "(检查|排查|诊断|体检|巡检|看看|分析).*(系统|服务器|主机|本机|机器|环境|状况)|"
                    + "(系统|服务器|主机|本机|环境).*(检查|排查|诊断|健康|状态|怎么样|如何|正常)|"
                    + "健康检查|运维检查|全面检查|帮我运维|系统状态|检查本机",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EXECUTE_FOLLOWUP = Pattern.compile(
            "(执行清理|按预览执行|真实删除|确认执行|开始清理|执行删除|执行修复|立即清理)",
            Pattern.CASE_INSENSITIVE);

    public enum Playbook {
        FULL_AUTONOMOUS,
        DISK_CLEANUP,
        CPU_PRESSURE,
        PATROL_CONTINUATION,
        NONE
    }

    public Playbook resolve(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Playbook.NONE;
        }
        if (PATROL_CONTINUE.matcher(userMessage).find()) {
            return Playbook.PATROL_CONTINUATION;
        }
        // 磁盘/CPU 等场景 Playbook 优先于宽泛的「自动运维」话术
        if (CLEANUP_INTENT.matcher(userMessage).find() || EXECUTE_FOLLOWUP.matcher(userMessage).find()) {
            return Playbook.DISK_CLEANUP;
        }
        if (CPU_INTENT.matcher(userMessage).find() && !CLEANUP_INTENT.matcher(userMessage).find()) {
            return Playbook.CPU_PRESSURE;
        }
        if (SERVICE_FAILURE_INTENT.matcher(userMessage).find()) {
            return Playbook.FULL_AUTONOMOUS;
        }
        if (AUTONOMOUS_INTENT.matcher(userMessage).find()) {
            return Playbook.FULL_AUTONOMOUS;
        }
        return Playbook.NONE;
    }

    public boolean shouldOrchestrate(String userMessage) {
        return resolve(userMessage) != Playbook.NONE;
    }

    public boolean forceRemediate(String userMessage) {
        return userMessage != null && EXECUTE_FOLLOWUP.matcher(userMessage).find();
    }
}
