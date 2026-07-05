package com.award.log.agent;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 统一意图路由：决定走运维运行时编排还是 LLM 多步 Tool。
 * <p>规则优先于 LLM，保证磁盘/CPU/巡检等高频路径输出稳定。</p>
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
                    + "(磁盘|空间|硬盘|根分区|c盘).*(满|不足|紧张|告警|满了)|"
                    + "(disk|space).*(full|low|cleanup)|"
                    + "cleanup.*(disk|log|temp)|"
                    + "(预览|查看|诊断).*(清理|清除).*(临时|tmp|temp|日志|磁盘)|"
                    + "磁盘满了|空间不够|清理临时|"
                    + "(扫描|扫).*(磁盘|c盘|空间|文件|目录|热点|盘)|"
                    + "(照片|图片|文件|相册).*(c盘|磁盘|空间|备份|整理)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CPU_INTENT = Pattern.compile(
            "(cpu|负载|卡顿|慢|性能|进程).*(高|满|异常|问题|过高|太多)|"
                    + "(高负载|cpu高|cpu满了|内存紧张|系统慢|负载过高|占用过高|CPU占用)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERAL_OPS_CHECK = Pattern.compile(
            "(检查|排查|诊断|体检|巡检|看看|分析).*(系统|服务器|主机|本机|机器|环境|状况)|"
                    + "(系统|服务器|主机|本机|环境).*(检查|排查|诊断|健康|状态|怎么样|如何|正常)|"
                    + "健康检查|运维检查|全面检查|帮我运维|系统状态|检查本机|"
                    + "(帮我|请).*(管理|整理|运维).*(电脑|本机)|运维管家|电脑管家|系统管家|帮我管理电脑|帮我运维",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EXECUTE_FOLLOWUP = Pattern.compile(
            "(执行清理|按预览执行|真实删除|确认执行|开始清理|执行删除|执行修复|立即清理|直接删除|立即删除|马上删除|删掉|删了)",
            Pattern.CASE_INSENSITIVE);

    /** 用户明确只要预览、不要删除 */
    private static final Pattern PREVIEW_ONLY = Pattern.compile(
            "仅预览|只预览|不要删|不要删除|别删|先看看|预览一下|不要执行|dry-?run",
            Pattern.CASE_INSENSITIVE);

    /** 用户消息里带盘符绝对路径的删除意图，视为已确认写操作（仍过路径白名单）。 */
    private static final Pattern EXPLICIT_PATH_DELETE = Pattern.compile(
            "(删除|删掉|清除|移除)\\s*[「\"']?(/[\\w\\-.]+|[A-Za-z]:[/\\\\])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 明确要动手清理（非纯诊断），默认直接执行白名单内 Temp，不再强制二次确认。 */
    private static final Pattern DIRECT_CLEANUP_EXECUTE = Pattern.compile(
            "清理.*垃圾|删除.*垃圾|帮我清理|帮我删|清理磁盘|清理临时|释放.*空间|清垃圾|删垃圾|扫.*删",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public enum Playbook {
        PATROL_AUTOMATION,
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
            return Playbook.PATROL_AUTOMATION;
        }
        if (AUTONOMOUS_INTENT.matcher(userMessage).find()) {
            return Playbook.PATROL_AUTOMATION;
        }
        if (GENERAL_OPS_CHECK.matcher(userMessage).find()) {
            return Playbook.PATROL_AUTOMATION;
        }
        return Playbook.NONE;
    }

    public boolean shouldOrchestrate(String userMessage) {
        return resolve(userMessage) != Playbook.NONE;
    }

    public boolean shouldOrchestrateFromContext(
            String userMessage,
            java.util.List<com.award.log.service.impl.UnifiedAssistantService.ChatTurn> history) {
        if (shouldOrchestrate(userMessage)) {
            return true;
        }
        String combined = AssistantIntentSignals.recentConversationText(userMessage, history, 8);
        return shouldOrchestrate(combined);
    }

    public boolean forceRemediate(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        if (PREVIEW_ONLY.matcher(userMessage).find()) {
            return false;
        }
        if (EXECUTE_FOLLOWUP.matcher(userMessage).find()
                || EXPLICIT_PATH_DELETE.matcher(userMessage).find()
                || DIRECT_CLEANUP_EXECUTE.matcher(userMessage).find()) {
            return true;
        }
        // 「清理/删除/释放 + 垃圾|临时|空间」类诉求：默认真删（仍限 Temp 白名单 + 门禁）
        return CLEANUP_INTENT.matcher(userMessage).find()
                && !Pattern.compile("(检查|看看|分析|诊断|怎么样|如何)", Pattern.CASE_INSENSITIVE)
                .matcher(userMessage).find();
    }
}
