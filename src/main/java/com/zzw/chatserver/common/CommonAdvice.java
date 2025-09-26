package com.zzw.chatserver.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@ControllerAdvice
@RestController
@Slf4j
public class CommonAdvice {

    /**
     * 原始异常处理
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    R handleExceptionForErrorOne(Exception e, HttpServletRequest request) {
        log.info("Exception message：{}", e.getMessage());
        log.info("Exception from：{}", e.getCause());
        log.info("Exception print：{}", e);
        return R.error().code((HttpStatus.INTERNAL_SERVER_ERROR.value()))
                .message(e.getMessage());
    }


    /**
     * 自定义全局异常处理
     */
    @ExceptionHandler(GlobalException.class)
    R handleExceptionForErrorTwo(GlobalException e, HttpServletRequest request) {
        log.info("MyException message：{}", e.getMessage());
        log.info("MyException from：{}", e.getCause());
        log.info("MyException print：{}", e);
        return R.error().code(e.getCode())
                .message(e.getMessage());
    }
}
