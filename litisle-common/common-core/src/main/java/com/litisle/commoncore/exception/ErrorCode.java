package com.litisle.commoncore.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举
 * 
 * 【设计说明】
 * 1. 所有服务的错误码都集中在此类定义，避免各服务重复定义导致冲突
 * 2. 错误码分段管理：
 *    - 10000-19999：通用错误（参数校验、权限、认证等）
 *    - 20000-29999：用户模块错误
 *    - 30000-39999：内容模块错误
 *    - 40000-49999：社交模块错误
 *    - 50000-59999：支付模块错误
 *    - 60000-69999：商城模块错误
 *    - 70000-79999：审核模块错误
 *    - 80000-89999：AI 模块错误
    - 90000-99999：文件模块错误
 * 
 * 3. 前端可根据错误码范围快速定位是哪个模块的问题
 * 
 * @author litisle
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ==================== 通用错误 10000-19999 ====================
    /**
     * 成功
     */
    SUCCESS(0, "操作成功"),
    
    /**
     * 系统内部错误（未捕获的异常）
     */
    SYSTEM_ERROR(10000, "系统繁忙，请稍后重试"),
    
    /**
     * 参数校验失败（如 @Valid 校验不通过）
     */
    PARAM_ERROR(10001, "参数错误"),
    
    /**
     * 未登录或 Token 无效
     */
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    
    /**
     * 权限不足（如普通用户访问管理员接口）
     */
    FORBIDDEN(10003, "权限不足"),
    
    /**
     * 请求的资源不存在（如文章ID不存在）
     */
    NOT_FOUND(10004, "资源不存在"),
    
    /**
     * 请求过于频繁（限流触发）
     */
    TOO_MANY_REQUESTS(10005, "请求过于频繁，请稍后重试"),
    
    /**
     * 数据已存在（如用户名已注册）
     */
    DATA_EXISTS(10006, "数据已存在"),

    /**
     * 加密解密失败（如 AES 密钥错误、密文被篡改等）
     */
    ENCRYPTION_ERROR(10007, "加密解密失败"),

    /**
     * Token 已过期（JWT 超过 expiration 时间）
     */
    TOKEN_EXPIRED(10008, "登录已过期，请重新登录"),

    /**
     * Token 无效（格式错误、签名不匹配、被篡改等）
     */
    TOKEN_INVALID(10009, "令牌无效"),

    /**
     * Token 已被加入黑名单（用户主动退出或管理员踢人）
     */
    TOKEN_BLACKLISTED(10010, "令牌已被吊销"),


    /**
     * 验证码错误（图形验证码或邮箱验证码校验失败/已过期）
     */
    CAPTCHA_ERROR(10011, "验证码错误或已过期"),

    /**
     * 邮件发送失败（SMTP 异常或配置错误）
     */
    MAIL_SEND_ERROR(10012, "邮件发送失败，请稍后重试"),

    /**
     * 远程服务调用失败（Feign 调用其他微服务异常）
     */
    REMOTE_SERVICE_ERROR(10013, "远程服务调用失败"),

    /**
     * 无权限操作（非 owner 且非 ADMIN 操作他人资源）
     */
    NO_PERMISSION(10014, "无权限操作"),

    // ==================== 用户模块 20000-29999 ====================
    /**
     * 用户不存在
     */
    USER_NOT_FOUND(20001, "用户不存在"),
    
    /**
     * 用户名或密码错误
     */
    USER_PASSWORD_ERROR(20002, "用户名或密码错误"),
    
    /**
     * 手机号已被注册
     */
    PHONE_EXISTS(20003, "手机号已被注册"),
    
    /**
     * 账号已被冻结
     */
    USER_FROZEN(20004, "账号已被冻结"),
    
    /**
     * 账号已注销
     */
    USER_LOGGED_OFF(20005, "账号已注销"),
    
    /**
     * VIP 已过期
     */
    VIP_EXPIRED(20006, "VIP 已过期"),

    /**
     * 用户不存在或已注销
     */
    USER_NOT_EXIST_OR_CANCELLED(20007, "用户不存在或已注销"),

    /**
     * 用户名已存在
     */
    USERNAME_ALREADY_EXISTS(20008,"用户名已存在"),

    /**
     * 笔名已存在
     */
    PEN_NAME_ALREADY_EXISTS(20009,"笔名已存在"),

    /**
     * 该账号已绑定邮箱
     */
    EMAIL_ALREADY_BOUND(20010,"该账号已绑定邮箱"),

    /**
     * 该账号未绑定邮箱
     */
    EMAIL_NOT_BOUND(20011,"该账号未绑定邮箱"),

    /**
     * 邮箱已被其他账号绑定
     */
    EMAIL_EXISTS(20012,"邮箱已被其他账号绑定"),

    // ==================== 内容模块 30000-39999 ====================
    /**
     * 文章不存在
     */
    ARTICLE_NOT_FOUND(30001, "文章不存在"),
    
    /**
     * 文章已下架
     */
    ARTICLE_OFFLINE(30002, "文章已下架"),
    
    /**
     * 草稿不存在
     */
    DRAFT_NOT_FOUND(30003, "草稿不存在"),
    
    /**
     * 书籍不存在
     */
    BOOK_NOT_FOUND(30004, "书籍不存在"),

    // ==================== 社交模块 40000-49999 ====================
    /**
     * 评论不存在
     */
    COMMENT_NOT_FOUND(40001, "评论不存在"),
    
    /**
     * 已点赞
     */
    ALREADY_LIKED(40002, "已点赞"),
    
    /**
     * 已关注
     */
    ALREADY_FOLLOWED(40003, "已关注"),

    

    // ==================== 支付模块 50000-59999 ====================
    /**
     * 余额不足
     */
    INSUFFICIENT_BALANCE(50001, "余额不足"),
    
    /**
     * 订单不存在
     */
    ORDER_NOT_FOUND(50002, "订单不存在"),
    
    /**
     * 订单已支付
     */
    ORDER_ALREADY_PAID(50003, "订单已支付"),

    // ==================== 商城模块 60000-69999 ====================
    /**
     * 商品不存在
     */
    PRODUCT_NOT_FOUND(60001, "商品不存在"),
    
    /**
     * 库存不足
     */
    STOCK_NOT_ENOUGH(60002, "库存不足"),
    
    /**
     * 不满足购买资格
     */
    PURCHASE_QUALIFICATION_FAILED(60003, "不满足购买资格"),

    // ==================== 审核模块 70000-79999 ====================
    /**
     * 内容包含敏感词
     */
    SENSITIVE_WORD_DETECTED(70001, "内容包含敏感词"),
    
    /**
     * 审核未通过
     */
    REVIEW_REJECTED(70002, "审核未通过"),

    // ==================== 文件模块 90000-99999 ====================
    /**
     * 文件大小超过限制
     */
    FILE_SIZE_EXCEEDED(90001, "文件大小超过限制"),

    /**
     * 文件类型不允许（MIME 白名单校验失败）
     */
    FILE_TYPE_NOT_ALLOWED(90002, "文件类型不允许"),

    /**
     * 文件摘要计算失败（SHA256）
     */
    FILE_HASH_ERROR(90003, "文件摘要计算失败"),

    /**
     * 文件不存在
     */
    FILE_NOT_FOUND(90004, "文件不存在"),

    /**
     * 文件存储服务不可用（SeaweedFS 异常）
     */
    FILE_STORAGE_UNAVAILABLE(90005, "文件存储服务不可用，请稍后重试"),

    /**
     * 文件内容校验失败（存储侧 SHA256 比对不符，内容可能在传输中损坏）
     */
    FILE_CHECKSUM_MISMATCH(90006, "文件内容校验失败"),

    /**
     * 存储配额已用尽（per-user 总容量或文件数超限）
     */
    FILE_QUOTA_EXCEEDED(90007, "存储配额已用尽"),

    /**
     * 文件违规禁止访问（safe_flag=0，访问闸口拒绝；审核链路单点收口）
     */
    FILE_VIOLATIVE(90008, "文件违规，禁止访问"),

    /** 上传链路：命中审核黑名单/审核缓存 REJECT 结论，拒传（不落库不进 MQ） */
    /**
     * 文件审核中，暂不可访问（PUBLIC 高曝光面 safe_flag=2/3 在 download 流链路的专用错误码；
     * getUrl/batchGetUrls 同状态返回占位图 URL，下载流无占位语义故明确报错）
     */
    FILE_NOT_READY(90010, "文件审核中，暂不可访问"),
    FILE_UPLOAD_REJECTED(90009, "图片未通过审核，禁止上传");



    /**
     * 错误码
     */
    private final int code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 根据错误码数值查找对应的枚举常量
     *
     * 【设计说明】
     * 1. 企业规范：提供 code → enum 的反查能力，用于 BizException(code) 构造后恢复 ErrorCode 引用
     * 2. 使用场景：JwtAuthGlobalFilter 捕获 BizException 后，通过 e.getCode() 获取错误码数值，
     *    再用此方法还原 ErrorCode 枚举，传递给 unauthorized() 方法
     * 3. 性能：枚举值通常较少，遍历查找开销可忽略
     *
     * @param code 错误码数值（如 10008、20001）
     * @return 匹配的 ErrorCode 枚举常量
     * @throws IllegalArgumentException 未找到匹配的枚举时抛出（运行时异常，不应发生）
     */
    public static ErrorCode getByCode(int code) {
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        // 未找到匹配的枚举，返回兜底错误码而非抛异常
        return SYSTEM_ERROR;
    }
}