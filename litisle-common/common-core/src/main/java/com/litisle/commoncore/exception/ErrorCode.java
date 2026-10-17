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

    /**
     * MQ 事件发送失败（common-mq sendCritical 必达语义，重试后仍失败时抛出，
     * 调用方需回滚事务或执行补偿逻辑；尽力而为型 send 不使用此码）
     */
    MQ_SEND_FAILED(10015, "消息发送失败，请稍后重试"),

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

    /**
     * 标题或内容为空（发布前置校验 4-1.1，标题 trim 非空 + 正文去标签可见文本非空）
     */
    CONTENT_EMPTY(30005, "标题或内容为空"),

    /**
     * 标题超长（发布前置校验 4-1.1，上限100字符，对齐 article.title varchar(100)）
     */
    TITLE_TOO_LONG(30006, "标题超过100个字符"),

    /**
     * 文章参数校验失败（标题>100、去标签可见文本为空、正文/配图同空、方案B 禁改正文/标题）
     */
    ARTICLE_PARAM_INVALID(30007, "文章参数校验失败"),

    /**
     * 分区权限拒绝（zone=2 非 AUTHOR、zone=3 非 ADMIN/运营、一篇仅一分区冲突拒绝）
     */
    ARTICLE_ZONE_DENIED(30008, "无该分区发布权限"),

    /**
     * 插图引用被拒（fileId 非本人上传或 bizType≠ARTICLE_IMG，防引用他人文件/违规图）
     */
    ARTICLE_IMG_REFUSED(30009, "插图不可用：非本人上传或非文章配图"),

    /**
     * 草稿版本冲突（baseVersion 落后 draft_version，拒绝后写覆盖先写）
     */
    ARTICLE_DRAFT_CONFLICT(30010, "草稿版本已更新，请刷新后重试"),

    /**
     * 批量发布超限（单批 > publish.batch-max=20）
     */
    ARTICLE_BATCH_EXCEEDED(30011, "批量发布超出单批上限"),

    /**
     * 非法章节归属（chapter_id 不归属上报的 book_id，拒绝伪造跨书章节）
     */
    BOOK_CHAPTER_MISMATCH(30012, "章节与书籍不匹配"),

    /**
     * 文章访问密码错误（BCrypt 比对失败；失败超限触发 TOO_MANY_REQUESTS 限频）
     */
    ARTICLE_PASSWORD_ERROR(30013, "访问密码错误"),

    /**
     * 文章标签数超限（单篇 > tag.max-per-article=5，防关键词堆砌）
     */
    ARTICLE_TAG_EXCEEDED(30014, "标签数量超过上限"),

    /**
     * 章节序号冲突（uk(book_id, chapter_no) 已有同序号章节，序号即排序键不重复；
     * 业务预检拒绝 + 并发双挂 DB uk 兑底转本码）
     */
    BOOK_CHAPTER_NO_CONFLICT(30015, "章节序号已被占用"),

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

    /**
     * 敏感词已存在（同场景下重复录入）
     */
    WORD_DUPLICATED(70003, "敏感词已存在"),

    // 70004 预留：审核任务已被他人认领（claimTask CAS 实现时启用）
    /**
     * 审核任务已被他人认领（claimTask CAS affected=0 时抛出）
     */
    TASK_ALREADY_CLAIMED(70004, "该审核任务已被他人认领"),

    /**
     * 敏感词不合法（长度≤64 且不得含空白/控制字符，DFA 构建前拦截）
     */
    WORD_INVALID(70005, "敏感词不合法"),

    /**
     * 敏感词不存在
     */
    WORD_NOT_FOUND(70006, "敏感词不存在"),



    /**
     * 敏感词导入失败（文件读取异常或解析失败）
     */
    WORD_IMPORT_FAILED(70007, "敏感词导入失败"),

    /**
     * 审核任务不存在（文本机审按 biz_type+biz_id+scene 三元组定位 review_task 落空；
     * 消费端捕获后按业务性失败 ack 丢弃，防毒丸重投）
     */
    MODERATION_TASK_NOT_FOUND(70008, "审核任务不存在"),

    /**
     * 内容指纹不一致（送审事件 contentHash 与任务/正文错配，疑似旧消息打新任务；
     * 消费端捕获后按业务性失败 ack 丢弃）
     */
    MODERATION_HASH_MISMATCH(70009, "内容指纹不一致，疑似旧消息错配"),

    /**
     * 申诉不存在（裁决按 appealId 查询落空）
     */
    APPEAL_NOT_FOUND(70010, "申诉不存在"),

    /**
     * 原驳回裁决人不得裁决该内容申诉（回避红线，含 ADMIN）
     */
    APPEAL_JUDGE_FORBIDDEN(70011, "原裁决人不得裁决该申诉"),

    /**
     * 高风险裁决缺少显式二次确认（撤销封禁/违规分清零需 confirm=true 双签）
     */
    APPEAL_CONFIRM_REQUIRED(70012, "高风险裁决需二次确认"),

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