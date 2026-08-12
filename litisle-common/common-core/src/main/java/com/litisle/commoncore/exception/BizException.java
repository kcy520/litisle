package com.litisle.commoncore.exception;

import lombok.Getter;

/**
 * 业务异常类
 *
 * 【设计说明】
 * 1. 所有业务逻辑中的异常都应该抛 BizException，而不是 Exception
 * 2. 通过 ErrorCode 枚举统一管理错误码，避免硬编码
 * 3. GlobalExceptionHandler 会捕获此异常并转换为统一响应格式
 *
 * 【使用示例】
 * // 方式1：直接传错误码枚举
 * throw new BizException(ErrorCode.USER_NOT_FOUND);
 *
 * // 方式2：传错误码 + 自定义消息（覆盖枚举默认消息）
 * throw new BizException(ErrorCode.USER_NOT_FOUND, "该用户ID不存在: " + userId);
 *
 * // 方式3：传动态错误码和消息
 * throw new BizException(40001, "用户名已存在");
 *
 * @author litisle
 */
@Getter
public class BizException extends RuntimeException {

    /**
     * 错误码
     * 【说明】前端根据此码判断具体错误类型，如 10001=用户不存在、20001=文章不存在等
     */
    private final int code;

    /**
     * 构造方法1：通过 ErrorCode 枚举创建异常
     *
     * @param errorCode 错误码枚举，包含 code 和 message
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 构造方法2：通过 ErrorCode 枚举创建异常，但使用自定义消息
     *
     * 【使用场景】枚举的默认消息不够详细时，可以动态拼接更多信息
     * 例如：throw new BizException(ErrorCode.USER_NOT_FOUND, "用户ID=" + userId + "不存在");
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 构造方法3：直接传错误码和消息
     *
     * 【使用场景】错误码不在预定义枚举中，需要动态指定
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造方法4：通过 ErrorCode 枚举创建异常，保留原始异常链
     *
     * 【使用场景】包装底层异常（如 IOException、SQLException）时保留完整堆栈
     * 例如：catch (IOException e) { throw new BizException(ErrorCode.SYSTEM_ERROR, e); }
     *
     * @param errorCode 错误码枚举
     * @param cause     原始异常
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }

    /**
     * 构造方法5：通过 ErrorCode 枚举 + 自定义消息，保留原始异常链
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

}