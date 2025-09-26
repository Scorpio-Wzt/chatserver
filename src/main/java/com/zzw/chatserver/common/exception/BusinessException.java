package com.zzw.chatserver.common.exception;

import com.zzw.chatserver.common.ResultEnum; // 导入ResultEnum
import java.io.Serializable;

/**
 * 业务异常类：仅用于抛出业务层面的错误（如“用户不存在”“订单状态异常”）
 * 区别于系统异常（如空指针、数据库异常），业务异常需携带明确的错误码和用户可理解的消息
 */
public class BusinessException extends RuntimeException implements Serializable {
    // 序列化版本号：避免反序列化时因版本不一致报错
    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码：用于前端区分不同错误场景（如 2001=用户不存在，2002=订单状态异常）
     * 建议在 CommonErrorCode 枚举中统一管理错误码
     */
    private Integer code;

    /**
     * 错误消息：用户可理解的提示文本（如“该用户已被冻结，无法登录”）
     */
    private String message;

    // ------------------------------ 构造方法：覆盖不同使用场景 ------------------------------

    /**
     * 空构造：默认错误码 500（业务异常默认码），默认消息“业务处理失败”
     */
    public BusinessException() {
        this.code = 500;
        this.message = "业务处理失败";
    }

    /**
     * 仅传错误消息：默认错误码 500
     * @param message 错误消息（如“订单编号不存在”）
     */
    public BusinessException(String message) {
        super(message); // 父类 RuntimeException 携带消息，支持异常链追踪
        this.code = 500;
        this.message = message;
    }

    /**
     * 传错误码+错误消息：最常用场景（明确指定错误码和消息）
     * @param code 错误码（如 2001=用户不存在）
     * @param message 错误消息（如“未查询到该用户信息”）
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /**
     * 通过ResultEnum创建异常（使用枚举的错误码和消息）
     * @param resultEnum 错误枚举（包含code和message）
     */
    public BusinessException(ResultEnum resultEnum) {
        super(resultEnum.getMessage()); // 父类携带枚举的消息
        this.code = resultEnum.getCode(); // 从枚举获取错误码
        this.message = resultEnum.getMessage(); // 从枚举获取消息
    }

    /**
     * 通过ResultEnum+自定义消息创建异常（使用枚举的错误码，覆盖消息）
     * @param resultEnum 错误枚举（提供code）
     * @param message 自定义错误消息（覆盖枚举默认消息）
     */
    public BusinessException(ResultEnum resultEnum, String message) {
        super(message); // 父类携带自定义消息
        this.code = resultEnum.getCode(); // 从枚举获取错误码
        this.message = message; // 使用自定义消息
    }

    /**
     * 传错误消息+根因异常：用于异常链传递（如捕获数据库异常后包装为业务异常）
     * @param message 错误消息
     * @param cause 根因异常（如 SQLException、NullPointerException）
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause); // 父类携带根因，便于日志排查
        this.code = 500;
        this.message = message;
    }

    /**
     * 传错误码+错误消息+根因异常：完整场景（明确码+消息+根因）
     * @param code 错误码
     * @param message 错误消息
     * @param cause 根因异常
     */
    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }

    // ------------------------------ Getter 方法：供全局异常处理器获取属性 ------------------------------
    @Override
    public String getMessage() {
        return message;
    }

    public Integer getCode() {
        return code;
    }

    // 可选：Setter 方法（如需在异常抛出后修改码/消息，按需添加）
    public void setCode(Integer code) {
        this.code = code;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
