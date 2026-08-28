package com.litisle.commoncore.result;

import com.litisle.commoncore.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应结果封装类
 *
 * 【设计说明】
 * 1. 所有 Controller 方法的返回值都应包装为 Result<T>
 * 2. 前端根据 code 判断请求是否成功：
 *    - code = 0：成功
 *    - code != 0：失败，根据 code 展示对应错误提示
 *
 * 3. 提供静态工厂方法，简化使用：
 *    - Result.success()：无数据成功响应
 *    - Result.success(data)：带数据成功响应
 *    - Result.error(errorCode)：失败响应（使用枚举）
 *    - Result.error(code, message)：失败响应（自定义）
 *
 * 【泛型说明】
 * - T：响应数据的类型
 * - 如：Result<UserVO>、Result<List<ArticleVO>>、Result<Void>
 *
 * 【使用示例】
 * // 成功响应
 * return Result.success(userVO);
 *
 * // 失败响应
 * return Result.error(ErrorCode.USER_NOT_FOUND);
 *
 * @author litisle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    /**
     * 状态码
     * 0=成功，非0=失败
     */
    private int code;

    /**
     * 返回消息
     * 成功时通常为 "success"，失败时为具体错误信息
     */
    private String message;

    /**
     * 返回数据
     * 成功时携带业务数据，失败时通常为 null
     */
    private T data;

    /**
     * 判断请求是否成功
     *
     * @return true 表示成功（code == 0），false 表示失败
     */
    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }

    // ==================== 成功响应 ====================

    /**
     * 成功响应（无数据）
     *
     * @param <T> 泛型
     * @return Result 对象
     */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功响应（带数据）
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return Result 对象
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功响应（自定义消息 + 数据）
     *
     * @param message 自定义消息
     * @param data    响应数据
     * @param <T>     数据类型
     * @return Result 对象
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    // ==================== 失败响应 ====================

    /**
     * 失败响应（默认消息）
     *
     * @param <T> 泛型
     * @return Result 对象
     */
    public static <T> Result<T> error() {
        return new Result<>(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), null);
    }

    /**
     * 失败响应（自定义消息）
     *
     * @param message 错误消息
     * @param <T>     泛型
     * @return Result 对象
     */
    public static <T> Result<T> error(String message) {
        return new Result<>(ErrorCode.SYSTEM_ERROR.getCode(), message, null);
    }

    /**
     * 失败响应（自定义错误码和消息）
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     泛型
     * @return Result 对象
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败响应（使用 ErrorCode 枚举）
     *
     * 【推荐用法】统一使用枚举管理错误码，避免硬编码
     *
     * @param errorCode 错误码枚举
     * @param <T>       泛型
     * @return Result 对象
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }
}