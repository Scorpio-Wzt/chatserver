package com.zzw.chatserver.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用业务错误码枚举：统一管理所有业务异常的错误码和默认消息
 * 规则：
 * - 2000~2999：用户相关错误（如 2001=用户不存在，2002=用户被冻结）
 * - 3000~3999：订单相关错误（如 3001=订单不存在，3002=订单状态异常）
 * - 4000~4999：权限相关错误（如 4001=无权操作，4002=未登录）
 * - 5000~5999：系统业务错误（如 5001=参数校验失败）
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode {
    // ------------------------------ 用户相关 ------------------------------
    USER_NOT_EXIST(2001, "用户不存在"),
    USER_FROZEN(2002, "用户已被冻结，无法操作"),
    USER_PASSWORD_ERROR(2003, "用户名或密码错误"),

    // ------------------------------ 订单相关 ------------------------------
    ORDER_NOT_EXIST(3001, "订单不存在"),
    ORDER_STATUS_ILLEGAL(3002, "订单状态异常，无法执行该操作"),
    ORDER_CREATE_FAILED(3003, "订单创建失败，请重试"),

    // ------------------------------ 权限相关 ------------------------------
    NO_PERMISSION(4001, "无权操作，需具备对应权限"),
    NOT_LOGIN(4002, "用户未登录，请先登录"),

    // ------------------------------ 通用业务 ------------------------------
    PARAM_ERROR(5001, "参数校验失败，请检查输入"),
    BUSINESS_FAILED(5002, "业务处理失败，请重试");

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 默认错误消息
     */
    private final String message;
}