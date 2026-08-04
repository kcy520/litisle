package com.litisle.commoncore.exception;


import com.litisle.commoncore.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 
 * 【设计说明】
 * 1. @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 *    - 捕获所有 Controller 抛出的异常
 *    - 自动将返回值序列化为 JSON
 * 
 * 2. 异常处理优先级：精确匹配 > 父类匹配
 *    - 先匹配 BizException
 *    - 再匹配 MethodArgumentNotValidException（参数校验失败）
 *    - 最后匹配 Exception（兜底）
 * 
 * 3. 所有异常都会返回统一格式 Result<T>，前端按约定解析
 * 
 * 【学习要点】
 * - @ExceptionHandler：指定要处理的异常类型
 * - 方法返回值会序列化为 JSON（因为 @RestControllerAdvice）
 * - 日志记录：生产环境应输出到日志文件，便于排查问题
 * 
 * @author litisle
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     * 
     * 【触发场景】
     * - 代码中主动抛出 BizException
     * - 如：throw new BizException(ErrorCode.USER_NOT_FOUND)
     * 
     * @param e 业务异常
     * @return 统一响应格式
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常（@Valid 校验失败）
     * 
     * 【触发场景】
     * - Controller 方法参数使用 @Valid 注解
     * - 实体类字段使用 @NotBlank、@Size 等校验注解
     * - 校验不通过时自动抛出 MethodArgumentNotValidException
     * 
     * 【示例】
     * // Controller
     * public Result<Void> register(@Valid @RequestBody RegisterDTO dto) { ... }
     * 
     * // DTO
     * public class RegisterDTO {
     *     @NotBlank(message = "用户名不能为空")
     *     private String username;
     *     
     *     @Size(min = 6, max = 20, message = "密码长度6-20位")
     *     private String password;
     * }
     * 
     * @param e 参数校验异常
     * @return 统一响应格式，包含所有校验失败的字段信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        // 提取所有校验失败的字段和错误消息
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        
        log.warn("参数校验失败: {}", message);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 处理绑定异常（如 @ModelAttribute 绑定失败）
     * 
     * 【触发场景】
     * - GET 请求参数绑定到对象时类型不匹配
     * - 如：?age=abc（age 是 Integer 类型）
     * 
     * @param e 绑定异常
     * @return 统一响应格式
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        
        log.warn("参数绑定失败: {}", message);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 处理所有未捕获的异常（兜底）
     * 
     * 【设计说明】
     * 1. 防止系统抛出 500 错误页面，统一返回 JSON
     * 2. 记录完整堆栈信息，便于排查问题
     * 3. 返回给用户的消息应模糊化，避免暴露系统内部细节
     * 
     * @param e 异常
     * @return 统一响应格式
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}