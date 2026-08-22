package com.award.log.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局 REST 异常处理：将未捕获异常转为统一 {@link Result}，避免堆栈直接回前端。
 * RuntimeException 与 Exception 均返回 500；不在此做业务码细分。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public Result<String> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        String detail = e.getClass().getSimpleName()
                + (e.getMessage() == null || e.getMessage().isBlank() ? "" : (": " + e.getMessage()));
        return Result.error(500, "Business processing failed: " + detail);
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("Unhandled exception", e);
        String detail = e.getClass().getSimpleName()
                + (e.getMessage() == null || e.getMessage().isBlank() ? "" : (": " + e.getMessage()));
        return Result.error(500, "Internal server error: " + detail);
    }
}
