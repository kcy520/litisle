package com.litisle.litislereview.constant;

/**
 * 审核域 Redis 键常量（仅收敛 review 模块内多个组件共用的键定义）。
 *
 * 作用：把原先分散在各组件里各自写死的 Redis 键字面量集中到一处，避免只改一侧造成键口径漂移。
 */
public final class ReviewRedisKeyConstant {

    /**
     * 在途去重短锁 key 格式（review:dedup:{provider}:{contentHash}）。
     *
     * 【两个组件共用，禁止单边修改】
     * - 写入侧：ModerationTextService#tryLockInFlight 送审前用 SETNX 写入字符串值；
     * - 清扫侧：ReviewTaskSweeper 补偿重发前抢同一把锁，判断原请求是否仍在途。
     *
     * 任何一侧改键名、改占位符顺序或改值类型（例如换成 Redisson Hash 结构），必须两侧同一批修改。
     * 否则旧键 TTL 未过期时，另一侧用不匹配的命令访问同一个键会触发 WRONGTYPE 错误。
     *
     * 两段占位符均由服务端派生：provider 取枚举 code，contentHash 为 SHA256，禁止拼接用户原串。
     */
    public static final String REVIEW_DEDUP_KEY_FORMAT = "review:dedup:%s:%s";

    /** 工具类禁止实例化 */
    private ReviewRedisKeyConstant() {
    }
}
