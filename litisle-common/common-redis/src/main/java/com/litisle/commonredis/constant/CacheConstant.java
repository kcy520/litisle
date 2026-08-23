package com.litisle.commonredis.constant;



/**
 * 缓存 Key 前缀常量类
 * 
 * 【设计说明】
 * 1. 集中管理所有 Redis Key 的前缀，避免硬编码
 * 2. 统一格式：{模块}:{业务}:{标识}
 * 3. 各业务服务使用时，引用此常量 + 拼接具体业务 ID
 *    - 例：String key = CacheConstant.USER_TOKEN + userId;
 * 
 * 【Key 设计原则】
 * - 可读性强：看到 key 就知道是什么数据
 * - 避免冲突：不同模块的前缀不同
 * - 方便管理：按模块分组，便于批量删除
 * - 统一冒号分隔：Redis 官方推荐，在 RedisInsight 中自动分组显示
 * 
 * @author litisle
 */
public class CacheConstant {

    // ==================== 用户模块 ====================




    /**
     * 用户信息缓存
     * 格式：user:info:{userId}
     * 用途：缓存用户基本信息，减少 DB 查询
     */
    public static final String USER_INFO = "user:info:";

    /**
     * 用户角色缓存
     * 格式：user:role:{userId}
     * 用途：缓存用户角色列表（粗粒度：ADMIN、AUTHOR、READER）
     */
    public static final String USER_ROLE = "user:role:";

    /**
     * 用户权限缓存
     * 格式：user:permission:{userId}
     * 用途：缓存用户细粒度权限列表（如 user:delete、article:publish）
     *       配合 SecurityContext.permissions 字段和 @RequirePermission 注解使用
     *       litisle-auth 登录时写入缓存，业务服务从缓存读取后写入 SecurityContext.permissions
     *       角色变更时需同步刷新此缓存（避免权限缓存陈旧）
     */
    public static final String USER_PERMISSION = "user:permission:";

    /**
     * 用户 VIP 状态缓存
     * 格式：user:vip:{userId}
     * 用途：缓存 VIP 过期时间，判断 VIP 状态
     */
    public static final String USER_VIP = "user:vip:";

    /**
     * 用户资料扩展缓存
     * 格式：user:profile:{userId}
     * 用途：缓存用户笔名、头像标识等扩展资料
     */
    public static final String USER_PROFILE = "user:profile:";

    // ==================== 内容模块 ====================

    /**
     * 文章信息缓存
     * 格式：content:article:{articleId}
     * 用途：缓存文章基本信息
     */
    public static final String ARTICLE_INFO = "content:article:";

    /**
     * 文章浏览量计数
     * 格式：content:article:view:{articleId}
     * 用途：Redis 原子自增，异步落库
     */
    public static final String ARTICLE_VIEW_CNT = "content:article:view:";

    /**
     * 文章点赞数计数
     * 格式：content:article:like:{articleId}
     * 用途：Redis 原子自增，异步落库
     */
    public static final String ARTICLE_LIKE_CNT = "content:article:like:";

    // ==================== 社交模块 ====================

    /**
     * 关注者列表
     * 格式：social:follower:{userId}
     * 用途：Set 存储，查询用户的所有粉丝
     */
    public static final String SOCIAL_FOLLOWER = "social:follower:";

    /**
     * 关注对象列表
     * 格式：social:followee:{userId}
     * 用途：Set 存储，查询用户关注的所有人
     */
    public static final String SOCIAL_FOLLOWEE = "social:followee:";

    /**
     * 粉丝数
     * 格式：social:follower:count:{userId}
     * 用途：String 计数，快速展示
     */
    public static final String SOCIAL_FOLLOWER_CNT = "social:follower:count:";

    /**
     * 关注数
     * 格式：social:followee:count:{userId}
     * 用途：String 计数，快速展示
     */
    public static final String SOCIAL_FOLLOWEE_CNT = "social:followee:count:";

    /**
     * 点赞记录
     * 格式：social:like:{targetType}:{targetId}:{userId}
     * 用途：判断用户是否已点赞（1=已赞，0=未赞）
     */
    public static final String SOCIAL_LIKE = "social:like:";

    // ==================== 商城模块 ====================

    /**
     * 商品库存
     * 格式：shop:stock:{productId}
     * 用途：库存预扣，防超卖
     */
    public static final String SHOP_STOCK = "shop:stock:";

    /**
     * 商品限购记录
     * 格式：shop:limit:{productId}:{userId}
     * 用途：记录用户已购数量，控制限购
     */
    public static final String SHOP_LIMIT = "shop:limit:";

    // ==================== 通用业务 ====================

    /**
     * 分布式锁前缀
     * 格式：lock:{businessType}:{key}
     * 用途：所有分布式锁的 key 前缀
     */
    public static final String LOCK_PREFIX = "lock:";

    /**
     * 布隆过滤器前缀
     * 格式：bloom:{name}
     * 用途：布隆过滤器的 key 前缀
     */
    public static final String BLOOM_PREFIX = "bloom:";

    /**
     * 验证码缓存
     * 格式：captcha:{phone/email}
     * 用途：短信/邮箱验证码，TTL=5分钟
     */
    public static final String CAPTCHA = "captcha:";

    /**
     * 图形验证码
     * 格式：captcha:img:{uuid}
     * 用途：图形验证码，TTL=3分钟
     */
    public static final String CAPTCHA_IMG = "captcha:img:";

    /**
     * 邮箱验证码
     * 格式：captcha:email:{email}
     * 用途：邮箱验证码，TTL=5分钟
     */
    public static final String CAPTCHA_EMAIL = "captcha:email:";

    /**
     * 邮箱验证码防刷
     * 格式：captcha:email:limit:{ip}
     * 用途：同一 IP 每分钟发送次数限制，TTL=1分钟
     */
    public static final String CAPTCHA_EMAIL_LIMIT = "captcha:email:limit:";

    /**
     * 邮箱验证码校验失败计数
     * 格式：captcha:email:fail:{email}
     * 用途：连续校验失败计数，防止验证码被穷举爆破
     *       达到阈值后删除验证码，强制用户重新获取
     */
    public static final String CAPTCHA_EMAIL_FAIL = "captcha:email:fail:";

    /**
     * 登录失败次数
     * 格式：login:fail:{identifier}
     * 用途：记录连续登录失败次数，配合 LoginRetryLimiter 使用
     *       超过阈值后账户锁定，登录成功后通过 resetFailure() 清除
     *       identifier 可以是 username、phone、email
     */
    public static final String LOGIN_FAIL_CNT = "login:fail:";

    /**
     * 登录锁定标记
     * 格式：login:lock:{identifier}
     * 用途：账户锁定标记，达到最大失败次数后 SET，TTL 到期后自动解锁
     *       identifier 与 LOGIN_FAIL_CNT 保持一致
     */
    public static final String LOGIN_LOCK = "login:lock:";

    /**
     * 接口限流计数前缀
     * 格式：rate_limit:{type}:{id}:{method}
     * 用途：配合 @RateLimiter 注解，基于 Redis + Lua 脚本实现分布式限流
     *       - type：限流维度（user/ip/default）
     *       - id：用户ID / 客户端IP / global
     *       - method：类名:方法名
     *       完整 key 示例：rate_limit:user:1001:LoginController:login
     */
    public static final String RATE_LIMIT = "rate_limit:";

    /**
     * 幂等请求 Key 前缀
     * 格式：idempotent:{userId}:{methodPath}:{argsMd5}
     * 用途：配合 @Idempotent 注解，防止重复提交
     *       完整 key 示例：idempotent:1001:OrderController:createOrder:a1b2c3
     */
    public static final String IDEMPOTENT = "idempotent:";

    // ==================== 缓存默认过期时间（秒） ====================

    /** 5分钟 */
    public static final long TTL_5_MINUTES = 300L;

    /** 30分钟 */
    public static final long TTL_30_MINUTES = 1800L;

    /** 1小时 */
    public static final long TTL_1_HOUR = 3600L;

    /** 1天 */
    public static final long TTL_1_DAY = 86400L;

    /** 7天 */
    public static final long TTL_7_DAYS = 604800L;

    /** 30天 */
    public static final long TTL_30_DAYS = 2592000L;
}