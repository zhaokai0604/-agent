package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.LogTaskDiagnosisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.LogDiagnosisJobStore.Job;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/log/diagnosis")
public class LogTaskDiagnosisController {
    public record StartRequest(String requestId) {}
    public record CancelRequest(String runId) {}
    private final LogTaskDiagnosisService service;
    private final AnalysisTaskManager tasks;
    private final RequestUserResolver users;
    public LogTaskDiagnosisController(LogTaskDiagnosisService service, AnalysisTaskManager tasks, RequestUserResolver users) {
        this.service=service;this.tasks=tasks;this.users=users;
    }
    private void owner(HttpServletRequest request, String id) {
        if (!tasks.canAccessTask(id,users.currentUserId(request),users.isAdmin(request)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Task not found or access denied");
    }
    @GetMapping("/{taskId}") public Result<Job> status(HttpServletRequest request, @PathVariable String taskId) {
        owner(request,taskId);return Result.success(service.status(taskId));
    }
    @PostMapping("/{taskId}") public Result<Job> start(HttpServletRequest request, @PathVariable String taskId,
                                                     @RequestBody StartRequest payload) {
        owner(request,taskId);return Result.success(service.start(taskId,payload.requestId()));
    }
    @PostMapping("/{taskId}/cancel") public Result<Job> cancel(HttpServletRequest request, @PathVariable String taskId,
                                                             @RequestBody CancelRequest payload) {
        owner(request,taskId);return Result.success(service.cancel(taskId,payload.runId()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> badRequest() { return Result.error(400,"Invalid diagnosis request"); }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> conflict() { return Result.error(409,"Task state changed or diagnosis attempt budget exhausted"); }
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> missing() { return Result.error(404,"Task not found or access denied"); }
}
