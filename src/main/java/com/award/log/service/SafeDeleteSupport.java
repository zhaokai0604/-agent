package com.award.log.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * safe_delete：将文件移入回收站；回收站不可用时 fail-closed，绝不悄悄改成不可逆删除。
 */
@Slf4j
@Component
public class SafeDeleteSupport {

    private final OpsRecycleBinService recycleBinService;

    @Autowired(required = false)
    public SafeDeleteSupport(OpsRecycleBinService recycleBinService) {
        this.recycleBinService = recycleBinService;
    }

    public DeleteOutcome deleteFile(Path file, String traceId, boolean useRecycleBin) {
        if (file == null || !Files.exists(file)) {
            com.award.log.security.effect.FileApprovalScope.reject("审批对象已不存在");
            return DeleteOutcome.skipped("not_found");
        }
        if(!useRecycleBin && com.award.log.security.effect.FileApprovalScope.frozenCandidates()!=null) {
            com.award.log.security.effect.FileApprovalScope.reject("绑定的文件审批不允许永久删除");
            return DeleteOutcome.failed("审批对象仅允许进入回收站");
        }
        if (useRecycleBin && recycleBinService != null) {
            try {
                var expected = com.award.log.security.effect.FileApprovalScope.expected(file);
                String handle = expected == null ? recycleBinService.moveToRecycleBin(file, traceId)
                        : recycleBinService.moveToRecycleBin(file, traceId, expected);
                return DeleteOutcome.recycled(handle, file.toString());
            } catch (Exception e) {
                com.award.log.security.effect.FileApprovalScope.reject(e.getMessage());
                log.warn("回收站移动失败，拒绝不可逆删除: {} {}", file, e.getMessage());
                return DeleteOutcome.failed("recycle_failed: " + e.getMessage());
            }
        }
        if (useRecycleBin) {
            com.award.log.security.effect.FileApprovalScope.reject("回收站服务不可用");
            return DeleteOutcome.failed("recycle_service_unavailable");
        }
        try {
            Files.deleteIfExists(file);
            return Files.exists(file) ? DeleteOutcome.failed("delete_failed") : DeleteOutcome.deleted(file.toString());
        } catch (Exception e) {
            return DeleteOutcome.failed(e.getMessage());
        }
    }

    public boolean isRecyclePath(Path path) {
        return recycleBinService != null && recycleBinService.isRecyclePath(path);
    }

    public record DeleteOutcome(String mode, boolean success, String handle, String path, String error) {
        static DeleteOutcome recycled(String handle, String path) {
            return new DeleteOutcome("RECYCLE", true, handle, path, "");
        }

        static DeleteOutcome deleted(String path) {
            return new DeleteOutcome("DELETE", true, "", path, "");
        }

        static DeleteOutcome skipped(String reason) {
            return new DeleteOutcome("SKIP", false, "", "", reason);
        }

        static DeleteOutcome failed(String error) {
            return new DeleteOutcome("ERROR", false, "", "", error);
        }
    }
}
