package com.award.log.util;

import com.award.log.model.LogAlarm;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

/**
 * 邮箱推送工具类
 * 基于Spring Mail实现，支持HTML格式告警
 */
@Slf4j
@Component
public class EmailPushUtil {

    @Value("${spring.mail.username}")
    private String fromEmail;
    
    @Value("${email.alias:日志分析系统}")
    private String emailAlias;
    
    private final JavaMailSender mailSender;
    
    public EmailPushUtil(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    /**
     * 发送告警邮件
     * @param alarm 告警信息
     * @return 发送结果
     */
    public boolean sendAlarm(LogAlarm alarm, String toEmail) {
        log.info("开始发送告警邮件，告警ID: [{}]", alarm.getAlarmId());
        
        try {
            // 1. 构建MimeMessage
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            // 2. 设置邮件基本信息
            helper.setFrom(fromEmail, emailAlias);
            helper.setSubject(String.format("【日志告警】%s级别告警", alarm.getLevel()));
            
            if (toEmail == null || toEmail.isEmpty()) {
                log.error("【邮箱推送】无法获取邮箱地址，停止发送邮件");
                return false;
            }
            
            helper.setTo(toEmail);
            
            // 3. 构建HTML邮件内容
            String htmlContent = buildHtmlContent(alarm);
            helper.setText(htmlContent, true);
            
            // 4. 发送邮件
            mailSender.send(message);
            
            log.info("告警邮件发送成功，告警ID: [{}]", alarm.getAlarmId());
            return true;
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("发送告警邮件失败，告警ID: [{}], 异常信息: [{}]", 
                    alarm.getAlarmId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 构建HTML格式的邮件内容
     * @param alarm 告警信息
     * @return HTML内容
     */
    private String buildHtmlContent(LogAlarm alarm) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<title>日志告警</title>");
        html.append("<style>");
        html.append("body { font-family: 'Microsoft YaHei', Arial, sans-serif; margin: 0; padding: 0; background-color: #f4f4f4; }");
        html.append(".container { max-width: 800px; margin: 20px auto; background-color: #fff; border-radius: 8px; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1); overflow: hidden; }");
        html.append(".header { background-color: #409EFF; color: #fff; padding: 20px; text-align: center; }");
        html.append(".header h1 { margin: 0; font-size: 24px; }");
        html.append(".content { padding: 20px; }");
        html.append(".section { margin-bottom: 20px; padding: 15px; border-radius: 4px; background-color: #f9f9f9; }");
        html.append(".section h2 { margin: 0 0 10px 0; font-size: 18px; color: #333; border-bottom: 1px solid #ddd; padding-bottom: 5px; }");
        html.append(".alarm-info { margin-bottom: 15px; }");
        html.append(".alarm-info .label { display: inline-block; width: 100px; font-weight: bold; color: #666; }");
        html.append(".alarm-info .value { display: inline-block; color: #333; }");
        html.append(".log-content { background-color: #f0f0f0; padding: 15px; border-radius: 4px; font-family: Consolas, monospace; font-size: 14px; white-space: pre-wrap; word-break: break-all; }");
        html.append(".root-cause { color: #e6a23c; }");
        html.append(".solution { color: #67c23a; }");
        html.append(".footer { background-color: #f4f4f4; padding: 15px; text-align: center; font-size: 12px; color: #666; border-top: 1px solid #ddd; }");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"container\">");
        html.append("<div class=\"header\">");
        html.append("<h1>日志告警通知</h1>");
        html.append("</div>");
        html.append("<div class=\"content\">");
        
        // 告警基本信息
        html.append("<div class=\"section\">");
        html.append("<h2>告警信息</h2>");
        html.append("<div class=\"alarm-info\"><span class=\"label\">告警级别：</span><span class=\"value\">").append(alarm.getLevel()).append("</span></div>");
        html.append("<div class=\"alarm-info\"><span class=\"label\">告警时间：</span><span class=\"value\">").append(alarm.getCreateTime()).append("</span></div>");
        html.append("<div class=\"alarm-info\"><span class=\"label\">任务ID：</span><span class=\"value\">").append(alarm.getTaskId()).append("</span></div>");
        html.append("</div>");
        
        // 日志内容
        html.append("<div class=\"section\">");
        html.append("<h2>日志内容</h2>");
        html.append("<div class=\"log-content\">").append(alarm.getLogContent().replace("<", "&lt;").replace(">", "&gt;")).append("</div>");
        html.append("</div>");
        
        // 根因分析
        html.append("<div class=\"section\">");
        html.append("<h2>根因分析</h2>");
        html.append("<div class=\"root-cause\">").append(alarm.getRootCause()).append("</div>");
        html.append("</div>");
        
        // 解决方案
        html.append("<div class=\"section\">");
        html.append("<h2>解决方案</h2>");
        html.append("<div class=\"solution\">").append(alarm.getSolution()).append("</div>");
        html.append("</div>");
        
        html.append("</div>");
        html.append("<div class=\"footer\">");
        html.append("<p>此邮件由日志分析系统自动发送，请勿直接回复</p>");
        html.append("</div>");
        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        
        return html.toString();
    }
}