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
            return DeleteOutcome.skipped("not_found");
        }
        if (useRecycleBin && recycleBinService != null) {
            try {
                String handle = recycleBinService.moveToRecycleBin(file, traceId);
                return DeleteOutcome.recycled(handle, file.toString());
            } catch (Exception e) {
                log.warn("回收站移动失败，拒绝不可逆删除: {} {}", file, e.getMessage());
                return DeleteOutcome.failed("recycle_failed: " + e.getMessage());
            }
        }
        if (useRecycleBin) {
            return DeleteOutcome.failed("recycle_service_unavailable");
        }
        try {
            Files.deleteIfExists(file);
            return Files.exists(file) ? DeleteOutcome.failed("delete_failed") : DeleteOutcome.deleted(file.toString());
        } catch (Exception e) {
            return DeleteOutcome.failed(e.getMessage());
        }
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
