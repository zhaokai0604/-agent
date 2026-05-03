package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.util.AiChatResponseSupport;
import com.award.log.service.AiDiagnosisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Duration;

import reactor.core.publisher.Flux;

@Slf4j
@Service
public class AiDiagnosisServiceImpl implements AiDiagnosisService {

    @Autowired
    private ChatModel chatModel;

    private Prompt createPrompt(List<EnhancedLogParseResultEntity> anomalies) {
         StringBuilder logContent = new StringBuilder();
        for (EnhancedLogParseResultEntity logEntry : anomalies) {
            logContent.append(String.format("- 时间: %s | 等级: %s | 内容: %s | 疑似原因: %s\n",
                    logEntry.getLogTime(),
                    logEntry.getSeverity(),
                    logEntry.getDesensitizedLog(),
                    String.join(" ", logEntry.getAnomalyReasons())));
        }

        String systemPrompt = "你担任 **Incident Response Lead（事件响应负责人）** 兼 **Staff SRE**：输入为经脱敏的异常日志切片，需在 **证据边界内** 给出可交付给变更管理委员会（CAB）的技术备忘录。\n\n" +
                "输出必须严格遵循下列栏目（使用 **加粗** 标签引导小节标题，勿使用 Markdown `#` 标题）：\n\n" +
                "**问题等级**：\n" +
                "依据 **严重度（Severity）× 紧急度（Urgency）** 矩阵给出 Critical / High / Medium / Low；注明判定所依赖的日志字段或频次证据。\n\n" +
                "**问题描述**：\n" +
                "用 **现象（Symptom）— 受影响组件— 推测影响面（Blast Radius）** 结构撰写；区分用户可见故障与内部降级。\n\n" +
                "**根因分析**：\n" +
                "列出 **2–3 条 RCA 假设**，逐条标注 **置信度（高/中/低）** 与 **反证所需观测**；禁止将单一日志行过度因果外推。\n\n" +
                "**问题修复**：\n" +
                "针对每条高置信度假设给出 **Mitigation（止血）** 与 **Corrective Fix（纠正）**；步骤须含 **验证命令/指标** 与 **回滚要点**。\n\n" +
                "**预防措施**：\n" +
                "从 **可观测性（SLO/SLI 告警）**、**自动化变更门禁**、**容量与韧性架构** 三方面给出可度量改进项。";

        String userPrompt = "以下是系统检测到的异常日志片段，请执行专业级根因分析：\n" + logContent.toString();
        
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        return new Prompt(List.of(systemMessage, userMessage));
    }

    @Override
    public String generateDiagnosis(List<EnhancedLogParseResultEntity> anomalies) {
        log.info("生成 AI 诊断报告，异常条目数: [{}]", anomalies == null ? 0 : anomalies.size());
        if (anomalies == null || anomalies.isEmpty()) {
            return "经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。";
        }
        try {
            String result = AiChatResponseSupport.textFrom(chatModel.call(createPrompt(anomalies)));
            log.info("AI 诊断报告生成成功");
            return result;
        } catch (Exception e) {
            log.error("AI Service 批量调用失败", e);
            return "诊断服务暂时不可用，请稍后再试。（错误信息：" + e.getMessage() + "）";
        }
    }

    @Override
    public String diagnoseSingleLog(EnhancedLogParseResultEntity logEntry) {
        log.info("单条日志快速诊断，日志等级: [{}]", logEntry.getSeverity());
        if (logEntry == null) return "无效的日志数据。";
        
        String systemPrompt = "你是 **On-call Principal Engineer**。对单条异常日志执行 **极致压缩（Ultra-compressed）RCA**，面向已在 bridges / Slack 上的值班工程师。\n" +
                "输出必须严格为三行以内（不使用 Markdown 标题）：\n" +
                "1. **核心问题**：一行 **Symptom → 最可信组件/服务**；若仅为征兆，前缀标注「疑似：」。\n" +
                "2. **专家建议**：一行 **Immediate Action**：给出可复制执行的命令或配置键路径级指引。\n" +
                "3. **风险预防**：一行 **Telemetry / Guardrail**：点明须补齐的指标、告警或发布门禁。\n" +
                "全文 ≤ 180 汉字当量；禁止空话套话与免责声明堆砌。";
        String userPrompt = String.format("日志内容: %s\n等级: %s\n来源: %s", 
                logEntry.getDesensitizedLog(), logEntry.getSeverity(), logEntry.getProtocol());

        try {
            Message systemMessage = new SystemMessage(systemPrompt);
            Message userMessage = new UserMessage(userPrompt);
            String result = AiChatResponseSupport.textFrom(chatModel.call(new Prompt(List.of(systemMessage, userMessage))));
            log.info("单条日志诊断成功");
            return result;
        } catch (Exception e) {
            log.error("单条日志诊断失败", e);
            return "诊断失败: " + e.getMessage();
        }
    }

    @Override
    public Flux<String> generateDiagnosisStream(List<EnhancedLogParseResultEntity> anomalies) {
        log.info("启动 AI 流式诊断报告生成...");
        if (anomalies == null || anomalies.isEmpty()) {
            return Flux.just("经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。");
        }
        try {
            return chatModel.stream(createPrompt(anomalies))
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> {
                        log.error("AI Stream 调用发生异常", e);
                        return Flux.just("诊断服务中断: " + e.getMessage());
                    });
        } catch (Exception e) {
            log.error("AI Stream 启动失败", e);
            return Flux.just("流式诊断启动失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> generateSingleLogDiagnosisStream(EnhancedLogParseResultEntity logEntry) {
        if (logEntry == null) {
            return Flux.just("无效的日志数据。");
        }
        
        String systemPrompt = "角色：**Tier-2 Operations Analyst**。对单条日志做 **流式友好的三段式处置摘要**，供控制台侧边栏实时展示。\n" +
                "硬性约束：恰好 **3 句完整陈述**，不用 Markdown 标题。\n" +
                "句 1 — **Root Cause Hypothesis**：组件 + 机制级描述（例：「连接池耗尽导致线程阻塞」），必要时前缀「高度疑似」。\n" +
                "句 2 — **Remediation**：给出 **一条** 主路径修复动作（命令 / 参数 / 重启粒度）。\n" +
                "句 3 — **Customer / Service Impact**：一句话刻画对用户 SLA 或上下游契约的影响边界。\n" +
                "禁止含糊形容词（「严重」「异常」）而不附着可观测实体。";

        String userPrompt = String.format("日志内容: %s\n日志等级: %s\n来源: %s", 
                logEntry.getDesensitizedLog(), logEntry.getSeverity(), logEntry.getProtocol());

        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        try {
            return chatModel.stream(prompt)
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("AI 诊断暂时不可用: " + e.getMessage()));
        } catch (Exception e) {
            return Flux.just("请求失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> chatStream(String userMessageText) {
        String systemPrompt = "## Persona\n" +
                "**Distinguished Cloud & Platform Engineer**：贯通 Linux（含企业发行版与麒麟系）、Windows Server、Kubernetes、容器运行时与托管中间件；熟悉 **Well-Architected** 可靠性支柱与 **CRE（Customer Reliability Engineering）** 沟通范式。\n\n" +
                "## Operating Principles\n" +
                "1. **Precision over verbosity**：术语对齐 CNCF / POSIX / ITIL 常用语义场；拒绝口语化堆砌。\n" +
                "2. **Executable artifacts**：默认给出 **可复制** 的 CLI / Yaml / RegEx / PromQL 级示例，并注释参数风险。\n" +
                "3. **Systems thinking**：从 **数据面 / 控制面 / 依赖拓扑** 三联视角拆解问题；指出单点故障与序列化瓶颈。\n" +
                "4. **Structured response**：Markdown；复杂议题采用 **现状评估 → 风险矩阵 → 分阶段路线图**。\n" +
                "5. **Risk disclosure**：涉及数据迁移、证书轮转、网络 ACL 变更时，显式列出 **Rollback & Verification**。";
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userMessageText);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));
        
        try {
             return chatModel.stream(prompt)
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("服务异常: " + e.getMessage()));
        } catch (Exception e) {
             return Flux.just("请求失败: " + e.getMessage());
        }
    }
}
