package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
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

        String systemPrompt = "你是一位顶尖的站点可靠性工程师（SRE）和系统架构专家，拥有10年以上大规模分布式系统运维经验。你的任务是基于提供的异常日志片段，执行深度根因分析并生成符合行业标准的专业诊断报告。\n" +
                "请严格按照以下结构化格式输出（使用加粗标签标识部分，不使用Markdown标题）：\n\n" +
                "**问题等级**：\n" +
                "(基于SRE风险评估框架，评估整体风险等级：严重/Critical、高危/High、中等/Medium、低/Low，并提供数据支持的理由)\n\n" +
                "**问题描述**：\n" +
                "(结合日志上下文，使用专业术语精准描述系统故障现象，明确涉及的组件、服务及潜在的影响范围)\n\n" +
                "**根因分析**：\n" +
                "(基于日志模式识别和系统架构知识，提出2-3个最可能的根因假设，并按可能性排序)\n\n" +
                "**问题修复**：\n" +
                "(针对每个根因假设，提供具体、可操作的技术修复方案，包括命令、配置修改、服务重启等详细步骤)\n\n" +
                "**预防措施**：\n" +
                "(从监控、告警、自动化、架构优化等维度，提出长期预防此类问题的策略)";

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
            String result = chatModel.call(createPrompt(anomalies)).getResult().getOutput().getText();
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
        
        String systemPrompt = "你是一位世界顶尖的系统架构专家和SRE，专注于高可用分布式系统的故障诊断与优化。请针对提供的单条异常日志，执行毫秒级分析并生成具有深度技术洞察力的专业诊断。\n" +
                "输出要求（严格遵循）：\n" +
                "1. **核心问题**：使用专业术语，一句话精准定位故障根因，包含具体组件或服务名称。\n" +
                "2. **专家建议**：提供可立即执行的技术修复方案，包括具体命令或配置修改建议。\n" +
                "3. **风险预防**：从监控、自动化、架构设计三个维度，提出系统性预防措施。\n" +
                "输出标准：语言必须专业、简洁、技术性强，总字数控制在150字以内，不使用Markdown标题。";
        String userPrompt = String.format("日志内容: %s\n等级: %s\n来源: %s", 
                logEntry.getDesensitizedLog(), logEntry.getSeverity(), logEntry.getProtocol());

        try {
            Message systemMessage = new SystemMessage(systemPrompt);
            Message userMessage = new UserMessage(userPrompt);
            String result = chatModel.call(new Prompt(List.of(systemMessage, userMessage))).getResult().getOutput().getText();
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
                    .map(response -> {
                        String content = response.getResult().getOutput().getText();
                        return content != null ? content : "";
                    })
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
        
        String systemPrompt = "你是一位经验丰富的系统运维专家，专注于快速故障定位与响应。你的任务是针对提供的单条系统日志执行实时分析，并生成高精度的诊断结论。\n" +
                "请严格遵循以下要求输出：\n" +
                "1. 使用专业术语，在3句话以内完成分析\n" +
                "2. 第一句：精准描述故障根因（Root Cause），包含具体组件\n" +
                "3. 第二句：提供可立即执行的修复方案（Fix），包含关键步骤\n" +
                "4. 第三句：简要说明潜在影响范围（Impact）\n" +
                "输出标准：直接给出结论，不使用Markdown标题，语言专业、简洁、可操作。";

        String userPrompt = String.format("日志内容: %s\n日志等级: %s\n来源: %s", 
                logEntry.getDesensitizedLog(), logEntry.getSeverity(), logEntry.getProtocol());

        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        try {
            return chatModel.stream(prompt)
                    .map(response -> {
                        String content = response.getResult().getOutput().getText();
                        return content != null ? content : "";
                    })
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("AI 诊断暂时不可用: " + e.getMessage()));
        } catch (Exception e) {
            return Flux.just("请求失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> chatStream(String userMessageText) {
        String systemPrompt = "你是一位拥有10年以上经验的高级系统运维工程师（Senior DevOps/SRE），精通Linux、Windows、Kubernetes、Docker、各类云服务及中间件技术栈。你专注于高可用架构设计、性能优化、故障诊断与自动化运维。\n" +
                "对话要求：\n" +
                "1. 使用专业术语和行业标准表述，展现深厚的技术底蕴\n" +
                "2. 提供具体、可执行的技术解决方案，包括命令、配置示例和操作步骤\n" +
                "3. 分析问题时从系统架构层面出发，提供全面的技术视角\n" +
                "4. 回答条理清晰，结构分明，重点突出\n" +
                "5. 对于复杂问题，提供分阶段的解决策略和风险评估";
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userMessageText);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));
        
        try {
             return chatModel.stream(prompt)
                    .map(response -> {
                        String content = response.getResult().getOutput().getText();
                        return content != null ? content : "";
                    })
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("服务异常: " + e.getMessage()));
        } catch (Exception e) {
             return Flux.just("请求失败: " + e.getMessage());
        }
    }
}
