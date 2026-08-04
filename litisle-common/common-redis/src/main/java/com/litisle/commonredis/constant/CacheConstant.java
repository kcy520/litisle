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
     * 用户 Token 黑名单
     * 格式：auth:token:blacklist:{userId}
     * 用途：用户退出登录后，将 Token 加入黑名单
     */
    public static final String TOKEN_BLACKLIST = "auth:token:blacklist:";

    /**
     * 用户登录 Session
     * 格式：auth:session:{userId}
     * 用途：记录用户在线设备信息
     */
    public static final String USER_SESSION = "auth:session:";

    /**
     * 用户信息缓存
     * 格式：user:info:{userId}
     * 用途：缓存用户基本信息，减少 DB 查询
     */
    public static final String USER_INFO = "user:info:";

    /**
     * 用户角色缓存
     * 格式：user:role:{userId}
     * 用途：缓存用户角色列表
     */
    public static final String USER_ROLE = "user:role:";

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
     * 登录失败次数
     * 格式：login:fail:{username}
     * 用途：记录连续登录失败次数，超过5次锁定15分钟
     */
    public static final String LOGIN_FAIL_CNT = "login:fail:";

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