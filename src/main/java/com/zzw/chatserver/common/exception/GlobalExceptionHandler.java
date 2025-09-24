package com.zzw.chatserver.common.exception;

import com.zzw.chatserver.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一捕获所有 Controller 层抛出的异常，返回标准化的 R 格式响应
 */
@RestControllerAdvice // 作用于所有 @RestController 注解的类
@Slf4j // 日志记录
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常：优先级最高（仅处理 BusinessException）
     */
    @ExceptionHandler(BusinessException.class)
    public R handleBusinessException(BusinessException e) {
        // 打印业务异常日志（级别为 INFO，非 ERROR，因为业务异常是预期内的）
        log.info("业务异常：code={}, message={}", e.getCode(), e.getMessage(), e);
        // 返回标准化错误响应
        return R.error()
                .code(e.getCode())
                .message(e.getMessage());
    }

    /**
     * 捕获系统异常：处理所有未被捕获的异常（如空指针、数据库异常）
     */
    @ExceptionHandler(Exception.class)
    public R handleSystemException(Exception e) {
        // 打印系统异常日志（级别为 ERROR，需排查问题）
        log.error("系统异常：message={}", e.getMessage(), e);
        // 返回默认系统错误响应（避免暴露敏感信息给前端）
        return R.error()
                .code(500)
                .message("系统繁忙，请稍后重试");
    }
}