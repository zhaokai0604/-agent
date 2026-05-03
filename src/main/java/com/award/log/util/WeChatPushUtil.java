package com.award.log.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.award.log.model.LogAlarm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信推送工具类
 * 负责企业微信token获取、消息发送逻辑
 */
@Slf4j
@Component
public class WeChatPushUtil {

    // 企业微信配置
    @Value("${wechat.corp-id}")
    private String corpId;
    
    @Value("${wechat.app-secret}")
    private String appSecret;
    
    @Value("${wechat.agent-id}")
    private Integer agentId;
    
    // Token缓存
    private static final ConcurrentHashMap<String, TokenInfo> TOKEN_CACHE = new ConcurrentHashMap<>();
    
    // Token有效期（企业微信token有效期为2小时，这里设置为1小时50分钟）
    private static final long TOKEN_EXPIRE_TIME = 1 * 60 * 60 * 1000 + 50 * 60 * 1000;
    
    // HttpClient实例
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    /**
     * 发送告警到企业微信
     * @param alarm 告警信息
     * @return 发送结果
     */
    public boolean sendAlarm(LogAlarm alarm, String toUser) {
        log.info("开始发送企业微信告警，告警ID: [{}]", alarm.getAlarmId());
        
        try {
            // 1. 获取企业微信Token
            String token = getAccessToken();
            
            // 2. 构建消息
            String message = buildAlarmMessage(alarm, toUser);
            
            // 3. 调用企业微信API发送消息
            String url = String.format("https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=%s", token);
            String response = sendHttpRequest(url, message);
            
            // 4. 处理响应
            return handleWeChatResponse(response);
        } catch (Exception e) {
            log.error("发送企业微信告警失败，告警ID: [{}], 异常信息: [{}]", 
                    alarm.getAlarmId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取企业微信AccessToken
     * @return AccessToken
     */
    private String getAccessToken() throws IOException, InterruptedException {
        String cacheKey = corpId + "_" + appSecret;
        TokenInfo tokenInfo = TOKEN_CACHE.get(cacheKey);
        
        // 检查token是否有效
        if (tokenInfo != null && tokenInfo.isValid()) {
            return tokenInfo.getToken();
        }
        
        // 从企业微信API获取新token
        String url = String.format(
                "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s",
                corpId, appSecret);
        
        String response = sendHttpRequest(url, null);
        JSONObject result = JSON.parseObject(response);
        
        if (result.getInteger("errcode") == 0) {
            String newToken = result.getString("access_token");
            // 缓存token
            TOKEN_CACHE.put(cacheKey, new TokenInfo(newToken, System.currentTimeMillis()));
            log.info("获取企业微信Token成功");
            return newToken;
        } else {
            log.error("获取企业微信Token失败，错误码: [{}], 错误信息: [{}]", 
                    result.getInteger("errcode"), result.getString("errmsg"));
            throw new RuntimeException("获取企业微信Token失败");
        }
    }
    
    /**
     * 构建告警消息
     * @param alarm 告警信息
     * @return 消息JSON字符串
     */
    private String buildAlarmMessage(LogAlarm alarm, String toUser) {
        JSONObject message = new JSONObject();
        message.put("touser", (toUser == null || toUser.isBlank()) ? "@all" : toUser);
        message.put("msgtype", "text");
        message.put("agentid", agentId);
        
        JSONObject textContent = new JSONObject();
        StringBuilder content = new StringBuilder();
        content.append("【日志告警】\n");
        content.append(String.format("告警级别: %s\n", alarm.getLevel()));
        content.append(String.format("告警时间: %s\n", alarm.getCreateTime()));
        content.append(String.format("任务ID: %s\n", alarm.getTaskId()));
        content.append("\n【日志内容】\n");
        content.append(alarm.getLogContent()).append("\n");
        content.append("\n【根因分析】\n");
        content.append(alarm.getRootCause()).append("\n");
        content.append("\n【解决方案】\n");
        content.append(alarm.getSolution());
        
        textContent.put("content", content.toString());
        message.put("text", textContent);
        
        return message.toJSONString();
    }
    
    /**
     * 发送HTTP请求
     * @param url 请求URL
     * @param requestBody 请求体
     * @return 响应结果
     */
    private String sendHttpRequest(String url, String requestBody) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10));
        
        if (requestBody != null) {
            requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", "application/json; charset=utf-8");
        } else {
            requestBuilder.GET();
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        return response.body();
    }
    
    /**
     * 处理企业微信响应
     * @param response 响应内容
     * @return 处理结果
     */
    private boolean handleWeChatResponse(String response) {
        JSONObject result = JSON.parseObject(response);
        if (result.getInteger("errcode") == 0) {
            log.info("企业微信告警发送成功");
            return true;
        } else {
            log.error("企业微信告警发送失败，错误码: [{}], 错误信息: [{}]", 
                    result.getInteger("errcode"), result.getString("errmsg"));
            return false;
        }
    }
    
    /**
     * Token信息类
     */
    private static class TokenInfo {
        private final String token;
        private final long createTime;
        
        public TokenInfo(String token, long createTime) {
            this.token = token;
            this.createTime = createTime;
        }
        
        public String getToken() {
            return token;
        }
        
        /**
         * 检查Token是否有效
         * @return 是否有效
         */
        public boolean isValid() {
            return System.currentTimeMillis() - createTime < TOKEN_EXPIRE_TIME;
        }
    }
}