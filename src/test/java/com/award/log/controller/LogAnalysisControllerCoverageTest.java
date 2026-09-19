package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiDiagnosisService;
import com.award.log.service.LogAnalysisService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogAnalysisControllerCoverageTest {

    @Mock private LogAnalysisService logAnalysisService;
    @Mock private AiDiagnosisService aiDiagnosisService;
    @Mock private AnalysisTaskManager taskManager;
    @Mock private RequestUserResolver requestUserResolver;

    private LogAnalysisController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new LogAnalysisController();
        ReflectionTestUtils.setField(controller, "logAnalysisService", logAnalysisService);
        ReflectionTestUtils.setField(controller, "aiDiagnosisService", aiDiagnosisService);
        ReflectionTestUtils.setField(controller, "taskManager", taskManager);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);
        request = new MockHttpServletRequest();
        lenient().when(requestUserResolver.currentUserId(any())).thenReturn(7);
        lenient().when(requestUserResolver.isAdmin(any())).thenReturn(false);
    }

    @Test
    void uploadEndpointsValidateEmptyFilesAndStartAsyncTasks() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.log", "text/plain", new byte[0]);
        assertEquals(500, controller.uploadLog(request, empty).getCode());
        assertEquals(500, controller.uploadLogs(request, null).getCode());
        assertEquals(500, controller.uploadLogs(request, new MockMultipartFile[0]).getCode());

        MockMultipartFile one = new MockMultipartFile("file", "folder\\error..log", "text/plain",
                "ERROR line\n".getBytes());
        Result<String> uploaded = controller.uploadLog(request, one);
        assertEquals(200, uploaded.getCode());
        assertNotNull(uploaded.getData());
        verify(taskManager).initTask(anyString(), eq(7), eq("error_log"));
        verify(logAnalysisService).startAnalysisAsync(any(), eq(uploaded.getData()));

        MockMultipartFile emptyBatch = new MockMultipartFile("files", "", "text/plain", new byte[0]);
        MockMultipartFile validBatch = new MockMultipartFile("files", "batch.log", "text/plain", "INFO\n".getBytes());
        Result<List<String>> batch = controller.uploadLogs(request, new MockMultipartFile[]{emptyBatch, validBatch});
        assertEquals(200, batch.getCode());
        assertEquals(1, batch.getData().size());
    }

    @Test
    void uploadAndTaskQueriesFailClosedOnStorageOrAccessErrors() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "failure.log", "text/plain", "x".getBytes()) {
            @Override
            public void transferTo(java.io.File dest) throws IOException {
                throw new IOException("disk full");
            }
        };
        assertEquals(500, controller.uploadLog(request, file).getCode());

        TaskInfo task = new TaskInfo();
        task.setTaskId("task-1");
        PageResult<EnhancedLogParseResultEntity> detailPage = new PageResult<>();
        detailPage.setList(List.of());
        detailPage.setTotal(0);
        PageResult<TaskInfo> taskPage = new PageResult<>();
        taskPage.setList(List.of());
        taskPage.setTotal(0);
        when(taskManager.getTaskForUser(eq("task-1"), eq(7), eq(false))).thenReturn(null);
        assertEquals(404, controller.getTaskStatus(request, "task-1").getCode());
        assertEquals(404, controller.getTaskReport(request, "task-1").getCode());
        assertEquals(404, controller.getTaskReportDetails(request, "task-1", 1, 20, false).getCode());

        when(taskManager.getTaskForUser(eq("task-1"), eq(7), eq(false))).thenReturn(task);
        when(taskManager.getTaskDetailsPage("task-1", 2, 3, true)).thenReturn(detailPage);
        assertEquals(200, controller.getTaskReportDetails(request, "task-1", 2, 3, true).getCode());
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(taskManager.getTasksPageForUser(eq(7), eq(true), eq(2), eq(3), eq("f"), eq("DONE"), eq("s"), eq("e")))
                .thenReturn(taskPage);
        assertEquals(200, controller.getHistory(request, 2, 3, "f", "DONE", "s", "e").getCode());
    }

    @Test
    void diagnosisAndDownloadCoverNotCompletedAndUnsupportedBranches() throws Exception {
        TaskInfo missing = new TaskInfo();
        missing.setTaskId("d1");
        when(taskManager.getTaskForUser(eq("d1"), eq(7), eq(false))).thenReturn(null);
        assertEquals(404, controller.performDiagnosis(request, "d1").getCode());

        when(taskManager.getTaskForUser(eq("d1"), eq(7), eq(false))).thenReturn(missing);
        assertEquals(404, controller.performDiagnosis(request, "d1").getCode());
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        controller.downloadReport(request, "d1", "pdf", missingResponse);
        assertEquals(400, missingResponse.getStatus());

        TaskInfo completed = new TaskInfo();
        completed.setTaskId("d2");
        completed.setStatus("COMPLETED");
        completed.setResult(List.of(new EnhancedLogParseResultEntity("ERROR log")));
        when(taskManager.getTaskForUser(eq("d2"), eq(7), eq(false))).thenReturn(completed);
        when(aiDiagnosisService.generateDiagnosisFromFullResult(any())).thenReturn("diagnosis");
        assertEquals(200, controller.performDiagnosis(request, "d2").getCode());
        verify(taskManager).updateAiDiagnosis("d2", "diagnosis");
        MockHttpServletResponse unsupported = new MockHttpServletResponse();
        controller.downloadReport(request, "d2", "pdf", unsupported);
        assertEquals(400, unsupported.getStatus());

        when(aiDiagnosisService.diagnoseSingleLog(any())).thenReturn("quick");
        assertEquals(200, controller.quickDiagnose(new EnhancedLogParseResultEntity("line")).getCode());
        when(aiDiagnosisService.diagnoseSingleLog(any())).thenThrow(new IllegalStateException("offline"));
        assertEquals(500, controller.quickDiagnose(new EnhancedLogParseResultEntity("line")).getCode());
    }

    @Test
    void taskLifecycleCoversAccessDenialSuccessAndServiceFailure() {
        when(taskManager.canAccessTask(anyString(), eq(7), eq(false))).thenReturn(false);
        assertEquals(404, controller.pauseTask(request, "t").getCode());
        assertEquals(404, controller.resumeTask(request, "t").getCode());
        assertEquals(404, controller.cancelTask(request, "t").getCode());

        when(taskManager.canAccessTask(anyString(), eq(7), eq(false))).thenReturn(true);
        assertEquals(200, controller.pauseTask(request, "t").getCode());
        assertEquals(200, controller.resumeTask(request, "t").getCode());
        assertEquals(200, controller.cancelTask(request, "t").getCode());

        doThrow(new IllegalStateException("pause failed")).when(logAnalysisService).pauseAnalysis("bad-pause");
        doThrow(new IllegalStateException("resume failed")).when(logAnalysisService).resumeAnalysis("bad-resume");
        doThrow(new IllegalStateException("cancel failed")).when(logAnalysisService).cancelAnalysis("bad-cancel");
        assertEquals(500, controller.pauseTask(request, "bad-pause").getCode());
        assertEquals(500, controller.resumeTask(request, "bad-resume").getCode());
        assertEquals(500, controller.cancelTask(request, "bad-cancel").getCode());
    }

    @Test
    void deleteAndDownloadAccessChecksAreExplicit() throws Exception {
        when(taskManager.deleteTaskForUser("missing", 7, false)).thenReturn(false);
        assertEquals(404, controller.deleteTask(request, "missing").getCode());
        when(taskManager.deleteTaskForUser("gone", 7, false)).thenThrow(new IllegalStateException("db down"));
        assertEquals(500, controller.deleteTask(request, "gone").getCode());

        TaskInfo task = new TaskInfo();
        task.setStatus("RUNNING");
        when(taskManager.getTaskForUser(eq("not-found"), eq(7), eq(false))).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.downloadReport(request, "not-found", "csv", response);
        assertEquals(404, response.getStatus());
    }
}
