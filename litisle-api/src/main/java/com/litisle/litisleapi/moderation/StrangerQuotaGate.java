package com.litisle.litisleapi.moderation;


import com.litisle.litisleapi.constant.ModerationConstant;

/**
 * 陌生人会话配额闸口（板块四 4-6.2 / Review-Plan §4.2.1）——口径实现
 * （Redis INCR / DB 条件更新双适配器由宿主注入，契约层零 redis/ORM 依赖，模式同 LastHashStore）。
 *
 * 【业务背景】陌生人私聊防骚扰：单会话累计最多 {@link ModerationConstant#STRANGER_QUOTA_LIMIT}=3 条；
 * 对方回复后即解除限制（回信语义由 message 模块落库时处理，本闸口只管计数扣减）。
 *
 * 【双层口径】Redis INCR 为主（高性能计数）；Redis 故障→降级 DB 原子条件更新
 * update conversation set a_quota_used=a_quota_used+1 where id=? and a_quota_used<3
 * ——禁读-改-写两段式（并发下必超发），affected=0 即超限。
 *
 * 【REJECT 仍占配额】DFA HIGH 同步拒发（70001）的消息不投递 MQ，但配额已扣减
 * ——投递行为已发生（先扣额后审核），防止攻击者用垃圾词探测式消耗配额不计成本；
 * 与 DfaPublishGate 的编排顺序：扣配额 → DFA 闸门 → 入库投递 → 异步机审。
 *
 * 【计数漂移防护】Redis 与 DB 双计数允许短暂漂移（Redis 为快照、DB 为权威）：
 * 降级窗口内 DB 条件更新保证不超发；Redis 恢复后首次成功 INCR 前须以 DB 值回填
 * （回填口径随 message 模块落地实现，当前仅留档）。
 *
 * 【挂起说明】litisle-message 模块不存在，配额扣减与发送链路嵌入全部挂起（契约实现已落）；
 * 触发条件同 3-5，message 模块正式开发时随实体层一并落地。
 *
 * @author litisle
 */
public final class StrangerQuotaGate {

    private StrangerQuotaGate() {
    }

    /**
     * 配额扣减：INCR 为主，Redis 故障降级 DB 条件更新（4-6.2）。
     *
     * @param conversationId 会话 ID（陌生人会话）
     * @param redis          Redis INCR 适配（宿主注入）
     * @param db             DB 条件更新适配（宿主注入，降级通道）
     * @return true-配额扣减成功（允许发送）；false-超限（发送入口统一拒绝，错误码随 message 模块定义）
     */
    public static boolean tryAcquire(long conversationId, RedisIncrAdapter redis, DbQuotaAdapter db) {
        // 步骤1：适配器判空 fail-fast（任一缺失都无法保证「不超发」防线，宁严勿松）
        if (redis == null || db == null) {
            throw new IllegalArgumentException("RedisIncrAdapter/DbQuotaAdapter 为空，禁止静默跳过配额闸口");
        }
        // 步骤2：入参 fail-fast（非法会话 ID 直拦，避免脏 key 写入 Redis）
        if (conversationId <= 0) {
            throw new IllegalArgumentException("conversationId 必须为正数");
        }
        // 步骤3：Redis INCR 为主计数（key 由 STRANGER_QUOTA_KEY 格式化，会话 ID 服务端派生数字，无注入面）
        Long newValue = redis.tryIncr(String.format(ModerationConstant.STRANGER_QUOTA_KEY, conversationId));
        // 步骤4：null=Redis 故障降级信号（适配器约定禁抛异常阻断发送链路）→ DB 原子条件更新兜底
        if (newValue == null) {
            // affected=0 即超限；「禁读-改-写两段式」由适配器 SQL 口径收口（见 DbQuotaAdapter 注）
            return db.incrIfUnderLimit(conversationId);
        }
        // 步骤5：INCR 新值≤上限放行；>上限拒绝（本次计数已自增属可接受漂移：DB 为权威口径，
        // 与 DB 路径 affected=0 不自增存在轻微不对称，不影响「累计最多 3 条」结论，漂移回填口径见类注）
        return newValue <= ModerationConstant.STRANGER_QUOTA_LIMIT;
    }

    /**
     * Redis INCR 适配（宿主注入，契约层不引 spring-data-redis）。
     */
    @FunctionalInterface
    public interface RedisIncrAdapter {

        /**
         * INCR 计数（key 见 {@link ModerationConstant#STRANGER_QUOTA_KEY}）。
         *
         * @param key 配额计数 key
         * @return INCR 后新值；Redis 故障返回 null（降级信号，禁抛异常阻断发送链路）
         */
        Long tryIncr(String key);
    }

    /**
     * DB 条件更新适配（宿主注入，降级通道）。
     */
    @FunctionalInterface
    public interface DbQuotaAdapter {

        /**
         * 原子条件更新（口径见类注）：a_quota_used=a_quota_used+1 where id=? and a_quota_used&lt;3。
         *
         * @param conversationId 会话 ID
         * @return true-affected=1（未超限，扣减成功）；false-affected=0（已超限）
         */
        boolean incrIfUnderLimit(long conversationId);
    }
}