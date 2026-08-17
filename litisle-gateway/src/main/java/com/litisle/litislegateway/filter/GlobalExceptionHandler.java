package com.litisle.litislegateway.filter;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.litisle.commoncore.exception.ErrorCode;

/**
 * Sa-Token 网关异常处理器
 *
 * 【说明】
 * 将 Sa-Token 异常转为统一 JSON 响应格式，与项目规范一致
 * 由 SaReactorFilter 内部回调调用，返回 JSON 字符串由框架写入响应
 *
 * @author litisle
 */
public class GlobalExceptionHandler {

    public static String handleException(Throwable e) {
        // 设置响应状态码和 Content-Type（通过 SaHolder 访问当前请求上下文）
        SaHolder.getResponse().setStatus(getHttpStatus(e));
        SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");

        ErrorCode errorCode = getErrorCode(e);
        String message = getMessage(e);

        return String.format(
                "{\"code\":%d,\"message\":\"%s\",\"data\":null}",
                errorCode.getCode(),
                escapeJson(message)
        );
    }

    private static int getHttpStatus(Throwable e) {
        if (e instanceof NotLoginException) {
            return 401;
        }
        if (e instanceof NotPermissionException) {
            return 403;
        }
        return 500;
    }

    private static ErrorCode getErrorCode(Throwable e) {
        if (e instanceof NotLoginException) {
            return com.litisle.commoncore.exception.ErrorCode.UNAUTHORIZED;
        }
        if (e instanceof NotPermissionException) {
            return com.litisle.commoncore.exception.ErrorCode.FORBIDDEN;
        }
        return com.litisle.commoncore.exception.ErrorCode.SYSTEM_ERROR;
    }

    private static String getMessage(Throwable e) {
        if (e instanceof NotLoginException) {
            NotLoginException ne = (NotLoginException) e;
            return switch (ne.getType()) {
                case NotLoginException.NOT_TOKEN -> "未登录，请先登录";
                case NotLoginException.INVALID_TOKEN -> "令牌无效，请重新登录";
                case NotLoginException.TOKEN_TIMEOUT -> "登录已过期，请重新登录";
                case NotLoginException.BE_REPLACED -> "账号已在其他设备登录，请重新登录";
                case NotLoginException.KICK_OUT -> "账号已被管理员强制下线";
                default -> "未登录或登录已过期";
            };
        }
        if (e instanceof NotPermissionException) {
            return "权限不足，禁止访问";
        }
        return e.getMessage();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}