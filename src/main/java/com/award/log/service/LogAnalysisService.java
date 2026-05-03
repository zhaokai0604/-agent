package com.award.log.service;

import java.io.File;

public interface LogAnalysisService {
    /**
     * 异步启动分析任务
     * @param logFile 本地临时文件
     * @param taskId 任务ID
     */
    void startAnalysisAsync(File logFile, String taskId);
    
    /**
     * 暂停分析任务
     * @param taskId 任务ID
     */
    void pauseAnalysis(String taskId);
    
    /**
     * 恢复分析任务
     * @param taskId 任务ID
     */
    void resumeAnalysis(String taskId);
    
    /**
     * 取消分析任务
     * @param taskId 任务ID
     */
    void cancelAnalysis(String taskId);
}