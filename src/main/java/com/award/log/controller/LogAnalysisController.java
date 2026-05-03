package com.award.log.controller;
import com.award.log.common.Result;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;
import com.award.log.service.AiDiagnosisService;

@Slf4j
@Tag(name = "Log Analysis Async")
@RestController
@RequestMapping("/log")
public class LogAnalysisController {

    @Autowired
    private LogAnalysisService logAnalysisService;

    @Autowired
    private AiDiagnosisService aiDiagnosisService;

    @Autowired
    private AnalysisTaskManager taskManager;

    @Operation(summary = "上传日志文件 (异步)")
    @PostMapping("/upload")
    public Result<String> uploadLog(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            log.warn("上传失败：文件为空");
            return Result.error("文件不能为空");
        }

        String taskId = UUID.randomUUID().toString();
        log.info("接收到日志上传请求，文件名: [{}], 生成任务ID: [{}]", file.getOriginalFilename(), taskId);
        try {
            // 使用系统临时目录存储上传的文件
            File tempDir = new File(System.getProperty("java.io.tmpdir"));      
            File destFile = new File(tempDir, taskId + "_" + file.getOriginalFilename());
            file.transferTo(destFile);
            log.info("文件已成功保存至临时路径: [{}]", destFile.getAbsolutePath());

            // 初始化任务并开始异步分析
            taskManager.initTask(taskId, file.getOriginalFilename());
            logAnalysisService.startAnalysisAsync(destFile, taskId);
            log.info("异步分析任务已启动，任务ID: [{}]", taskId);

            return Result.success(taskId);
        } catch (Exception e) {
            log.error("上传处理过程中发生异常, 任务ID: [{}]", taskId, e);
            return Result.error("上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "上传多个日志文件 (异步)")
    @PostMapping("/upload/multi")
    public Result<List<String>> uploadLogs(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            log.warn("上传失败：文件数组为空");
            return Result.error("文件不能为空");
        }

        List<String> taskIds = new ArrayList<>();
        log.info("接收到多文件上传请求，文件数量: [{}]", files.length);
        try {
            // 使用系统临时目录存储上传的文件
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    log.warn("跳过空文件");
                    continue;
                }
                
                String taskId = UUID.randomUUID().toString();
                taskIds.add(taskId);
                log.info("处理文件: [{}], 生成任务ID: [{}]", file.getOriginalFilename(), taskId);
                
                File destFile = new File(tempDir, taskId + "_" + file.getOriginalFilename());
                file.transferTo(destFile);
                log.info("文件已成功保存至临时路径: [{}]", destFile.getAbsolutePath());

                // 初始化任务并开始异步分析
                taskManager.initTask(taskId, file.getOriginalFilename());
                logAnalysisService.startAnalysisAsync(destFile, taskId);
                log.info("异步分析任务已启动，任务ID: [{}]", taskId);
            }

            return Result.success(taskIds);
        } catch (Exception e) {
            log.error("多文件上传处理过程中发生异常", e);
            return Result.error("上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "获取分析进度")
    @GetMapping("/task/{taskId}")
    public Result<TaskInfo> getTaskStatus(@PathVariable String taskId) {
        log.debug("查询任务状态，任务ID: [{}]", taskId);
        TaskInfo task = taskManager.getTask(taskId);
        if (task == null) {
            log.warn("查询失败：未找到任务ID为 [{}] 的任务", taskId);
            return Result.error("未找到相关任务");
        }
        
        // 直接返回完整任务信息，不做简化，确保前端能够获取最新状态
        return Result.success(task);
    }

    @Operation(summary = "分页获取历史任务")
    @GetMapping("/history")
    public Result<com.award.log.common.PageResult<TaskInfo>> getHistory(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        log.info("分页获取历史分析任务，页码: [{}], 每页条数: [{}], 文件名: [{}], 状态: [{}], 开始时间: [{}], 结束时间: [{}]", 
                pageNum, pageSize, fileName, status, startTime, endTime);
        return Result.success(taskManager.getTasksPage(pageNum, pageSize, fileName, status, startTime, endTime));
    }

    @Operation(summary = "获取最终分析报告")
    @GetMapping("/report/{taskId}")
    public Result<TaskInfo> getTaskReport(@PathVariable String taskId) {
        log.info("获取任务报告，任务ID: [{}]", taskId);
        TaskInfo task = taskManager.getTask(taskId);
        if (task == null) {
            log.warn("获取报告失败：未找到任务ID为 [{}] 的任务", taskId);
            return Result.error("未找到相关任务");
        }
        return Result.success(task);
    }

    @Operation(summary = "下载分析报告文件（按任务 ID，避免多任务覆盖导致下错文件）")
    @GetMapping("/download/{taskId}/{type}")
    public void downloadReport(@PathVariable String taskId, @PathVariable String type, jakarta.servlet.http.HttpServletResponse response) {
        log.info("请求下载分析报告，任务ID: [{}], 类型: [{}]", taskId, type);
        try {
            if (taskId == null || taskId.isBlank() || taskId.contains("..") || taskId.contains(File.separator) || taskId.contains("/") || taskId.contains("\\")) {
                response.sendError(400, "无效的任务 ID。");
                return;
            }
            TaskInfo task = taskManager.getTask(taskId);
            if (task == null) {
                log.warn("下载失败：任务不存在，任务ID: [{}]", taskId);
                response.sendError(404, "任务不存在。");
                return;
            }
            boolean isCsv = "csv".equalsIgnoreCase(type);
            if (!isCsv && !"html".equalsIgnoreCase(type)) {
                response.sendError(400, "不支持的导出类型，请使用 csv 或 html。");
                return;
            }
            String fileName = isCsv ? "log_analysis_result.csv" : "log_analysis_report.html";
            String filePath = System.getProperty("user.dir") + File.separator + "target" + File.separator + "output" + File.separator + taskId + File.separator + fileName;
            File file = new File(filePath);
            if (!file.exists()) {
                if ("COMPLETED".equalsIgnoreCase(task.getStatus())
                        && task.getResult() != null
                        && !task.getResult().isEmpty()) {
                    try {
                        log.info("报告文件缺失，尝试根据库内明细重新生成: [{}] -> [{}]", taskId, filePath);
                        logAnalysisService.ensureReportArtifacts(taskId, task.getResult());
                    } catch (java.io.IOException e) {
                        log.error("按需生成报告失败，任务ID: [{}]", taskId, e);
                        response.sendError(500, "生成报告文件失败。");
                        return;
                    }
                    file = new File(filePath);
                }
            }
            if (!file.exists()) {
                log.warn("下载失败：报告文件不存在，路径: [{}]", filePath);
                response.sendError(404, "报告文件尚未生成。");
                return;
            }

            response.setContentType(isCsv ? "text/csv;charset=UTF-8" : "text/html;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            
            try (java.io.InputStream is = new java.io.FileInputStream(file);
                 java.io.OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.flush();
            }
            log.info("报告文件 [{}] 下载成功", fileName);
        } catch (Exception e) {
            log.error("下载文件时发生异常", e);
        }
    }

    @Operation(summary = "执行 AI 故障诊断")
    @PostMapping("/diagnose/{taskId}")
    public Result<String> performDiagnosis(@PathVariable String taskId) {
        log.info("开始对任务 [{}] 执行 AI 智能诊断", taskId);
        try {
            TaskInfo task = taskManager.getTask(taskId);
            if (task == null || task.getResult() == null) {
                log.warn("AI 诊断失败：任务不存在或未完成，任务ID: [{}]", taskId);
                return Result.error("任务不存在或尚未完成分析。");
            }

            // 筛选异常日志作为上下文
            List<EnhancedLogParseResultEntity> anomalies = task.getResult().stream()
                    .filter(l -> l.isAnomaly() || l.getSeverity() == LogSeverityLevel.ERROR_LEVEL || l.getSeverity() == LogSeverityLevel.FATAL_LEVEL)
                    .sorted((o1, o2) -> Double.compare(o2.getAnomalyScore(), o1.getAnomalyScore()))
                    .limit(15)
                    .collect(Collectors.toList());

            log.info("提取到 [{}] 条关键异常日志用于 AI 分析", anomalies.size());
            String diagnosis = aiDiagnosisService.generateDiagnosis(anomalies);
            taskManager.updateAiDiagnosis(taskId, diagnosis);
            task.setAiDiagnosis(diagnosis); 
            
            log.info("任务 [{}] 的 AI 诊断报告已生成", taskId);
            return Result.success(diagnosis);
        } catch (Exception e) {
            log.error("AI 诊断执行失败，任务ID: [{}]", taskId, e);
            return Result.error("诊断失败: " + e.getMessage());
        }
    }

    @Operation(summary = "单条日志快速诊断")
    @PostMapping("/quick-diagnose")
    public Result<String> quickDiagnose(@RequestBody EnhancedLogParseResultEntity logEntry) {
        log.info("执行单条日志快速诊断");
        try {
            String result = aiDiagnosisService.diagnoseSingleLog(logEntry);
            return Result.success(result);
        } catch (Exception e) {
            log.error("快速诊断失败", e);
            return Result.error("诊断失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "暂停分析任务")
    @PostMapping("/pause/{taskId}")
    public Result<String> pauseTask(@PathVariable String taskId) {
        log.info("暂停任务请求，任务ID: [{}]", taskId);
        try {
            logAnalysisService.pauseAnalysis(taskId);
            return Result.success("任务已暂停");
        } catch (Exception e) {
            log.error("暂停任务失败，任务ID: [{}]", taskId, e);
            return Result.error("暂停任务失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "恢复分析任务")
    @PostMapping("/resume/{taskId}")
    public Result<String> resumeTask(@PathVariable String taskId) {
        log.info("恢复任务请求，任务ID: [{}]", taskId);
        try {
            logAnalysisService.resumeAnalysis(taskId);
            return Result.success("任务已恢复");
        } catch (Exception e) {
            log.error("恢复任务失败，任务ID: [{}]", taskId, e);
            return Result.error("恢复任务失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "取消分析任务")
    @PostMapping("/cancel/{taskId}")
    public Result<String> cancelTask(@PathVariable String taskId) {
        log.info("取消任务请求，任务ID: [{}]", taskId);
        try {
            logAnalysisService.cancelAnalysis(taskId);
            return Result.success("任务已取消");
        } catch (Exception e) {
            log.error("取消任务失败，任务ID: [{}]", taskId, e);
            return Result.error("取消任务失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "删除分析任务")
    @DeleteMapping("/delete/{taskId}")
    public Result<Boolean> deleteTask(@PathVariable String taskId) {
        log.info("删除任务请求，任务ID: [{}]", taskId);
        try {
            boolean success = taskManager.deleteTask(taskId);
            if (success) {
                return Result.success(true, "任务删除成功");
            } else {
                return Result.error("任务不存在或删除失败");
            }
        } catch (Exception e) {
            log.error("删除任务失败，任务ID: [{}]", taskId, e);
            return Result.error("删除任务失败: " + e.getMessage());
        }
    }
    

}
