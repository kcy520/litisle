package com.litisle.litisleapi.constant;

/**
 * 跨服务缓存 Key 前缀常量（业务跨服务层）。
 *
 * 【与 common 层常量的区分】
 * - common-core 的 CommonConstant / common-redis 的 CacheConstant：技术基建层，通用、与具体业务无关
 * - 本类：业务跨服务层，带业务语义，供多个业务服务共享读写同一份缓存
 *
 * 【命名规范】
 * 统一前缀 biz:，避免与 common 层 key 冲突
 * 格式：biz:{模块}:{业务}:{标识}
 *
 * @author litisle
 */
public class BizCacheKey {

    // ==================== 用户业务 ====================

    /** 用户公开信息缓存：biz:user:public:{userId} */
    public static final String USER_PUBLIC = "biz:user:public:";

    /** 用户角色缓存：biz:user:role:{userId} */
    public static final String USER_ROLE = "biz:user:role:";

    // ==================== 内容业务 ====================

    /** 文章详情缓存：biz:article:detail:{articleId} */
    public static final String ARTICLE_DETAIL = "biz:article:detail:";

    // ==================== 社交业务 ====================

    /** 粉丝数缓存：biz:social:follower:count:{userId} */
    public static final String SOCIAL_FOLLOWER_CNT = "biz:social:follower:count:";
}
