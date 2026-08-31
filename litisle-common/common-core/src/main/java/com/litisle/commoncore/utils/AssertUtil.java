package com.litisle.commoncore.utils;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;

import java.util.Collection;

/**
 * 断言工具类
 * 
 * 【设计说明】
 * 1. 提供常用断言方法，简化参数校验和状态校验
 * 2. 断言失败时自动抛出 BizException
 * 3. 相比 if + throw，代码更简洁
 * 
 * 【使用示例】
 * // 方式1：使用 if + throw
 * if (user == null) {
 *     throw new BizException(ErrorCode.USER_NOT_FOUND);
 * }
 * 
 * // 方式2：使用断言工具（推荐）
 * AssertUtil.notNull(user, ErrorCode.USER_NOT_FOUND);
 * 
 * @author litisle
 */
public class AssertUtil {

    /**
     * 断言对象不为 null，否则抛出业务异常
     * 
     * @param object    待检查对象
     * @param errorCode 错误码枚举
     * @param message   自定义消息（可选）
     */
    public static void notNull(Object object, ErrorCode errorCode, String... message) {
        if (object == null) {
            if (message.length > 0) {
                throw new BizException(errorCode, message[0]);
            }
            throw new BizException(errorCode);
        }
    }

    /**
     * 断言字符串不为空白（null、空串、空格），否则抛出业务异常
     * 
     * @param str       待检查字符串
     * @param errorCode 错误码枚举
     * @param message   自定义消息（可选）
     */
    public static void notBlank(String str, ErrorCode errorCode, String... message) {
        if (str == null || str.trim().isEmpty()) {
            if (message.length > 0) {
                throw new BizException(errorCode, message[0]);
            }
            throw new BizException(errorCode);
        }
    }


//    /**
//     * 断言集合不为 null 且不为空，否则抛出业务异常
//     *
//     * @param collection 待检查集合
//     */
//    public static void notEmpty(String... params) {
//        if (collection == null || collection.isEmpty()) {
//            throw new BizException(ErrorCode.PARAM_ERROR);
//        }
//    }


    /**
     * 断言条件为 true，否则抛出业务异常
     * 
     * 【使用示例】
     * AssertUtil.isTrue(user.getAge() >= 18, ErrorCode.PARAM_ERROR, "年龄必须满18岁");
     * 
     * @param expression 条件表达式
     * @param errorCode  错误码枚举
     * @param message    自定义消息（可选）
     */
    public static void isTrue(boolean expression, ErrorCode errorCode, String... message) {
        if (!expression) {
            if (message.length > 0) {
                throw new BizException(errorCode, message[0]);
            }
            throw new BizException(errorCode);
        }
    }

    /**
     * 断言条件为 false，否则抛出业务异常
     * 
     * 【使用示例】
     * AssertUtil.isFalse(exists, ErrorCode.DATA_EXISTS, "用户名已存在");
     * 
     * @param expression 条件表达式
     * @param errorCode  错误码枚举
     * @param message    自定义消息（可选）
     */
    public static void isFalse(boolean expression, ErrorCode errorCode, String... message) {
        isTrue(!expression, errorCode, message);
    }
}