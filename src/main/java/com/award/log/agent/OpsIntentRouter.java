package com.award.log.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 统一意图路由：决定走运维运行时编排还是 LLM 多步 Tool。
 */
@Component
public class OpsIntentRouter {

    private static final Pattern AUTONOMOUS_INTENT = Pattern.compile(
            "(自动运维|全自动|一键运维|一键巡检|自主运维|智能运维|自动察觉|自动发现|自动处理|自动修复|"
                    + "察觉.*问题|发现.*解决|detect\\s*and\\s*fix|autonomous\\s*ops|auto\\s*remediat)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PATROL_CONTINUE = Pattern.compile(
            "(继续处理|处理巡检|执行巡检|巡检待办|待确认方案|处理待办)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLEANUP_INTENT = Pattern.compile(
            "(清理|清除|删掉|删除|释放).*(垃圾|空间|磁盘|日志|临时|tmp|temp|log)|"
                    + "(磁盘|空间|硬盘).*(满|不足|紧张|告警)|"
                    + "(disk|space).*(full|low|cleanup)|"
                    + "cleanup.*(disk|log|temp)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CPU_INTENT = Pattern.compile(
            "(cpu|负载|卡顿|慢|性能).*(高|满|异常|问题)|"
                    + "(高负载|cpu高|内存紧张|系统慢)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXECUTE_FOLLOWUP = Pattern.compile(
            "(执行清理|按预览执行|真实删除|确认执行|开始清理|执行删除|执行修复)",
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
        if (AUTONOMOUS_INTENT.matcher(userMessage).find()) {
            return Playbook.FULL_AUTONOMOUS;
        }
        if (PATROL_CONTINUE.matcher(userMessage).find()) {
            return Playbook.PATROL_CONTINUATION;
        }
        if (CPU_INTENT.matcher(userMessage).find() && !CLEANUP_INTENT.matcher(userMessage).find()) {
            return Playbook.CPU_PRESSURE;
        }
        if (CLEANUP_INTENT.matcher(userMessage).find() || EXECUTE_FOLLOWUP.matcher(userMessage).find()) {
            return Playbook.DISK_CLEANUP;
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
