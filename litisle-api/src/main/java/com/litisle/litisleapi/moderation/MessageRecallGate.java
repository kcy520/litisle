package com.litisle.litisleapi.moderation;


/**
 * 私信撤回 CAS 口径（板块四 4-6.1 / Review-Plan §4.2.1）——口径实现
 * （DB CAS 经 RecallCasAdapter 由宿主注入，契约层零 ORM 依赖，模式同 LastHashStore.RedisAdapter）。
 *
 * 【CAS SQL 口径】update message set status=0 where id=? and sender_id=? and status=1：
 * 三条件缺一不可——id（目标消息）、sender_id=?（仅本人可撤，防越权撤他人消息）、
 * status=1（仅正常态可撤，已撤回/已拦截态重复撤回 no-op）；不物理删正文（留存审计），
 * 对方端仅见「消息已撤回」。
 *
 * 【与审核 CAS 并发互斥】审核消费端先发后审流转为 status=1→2（拦截态）、人审恢复 2→1；
 * 撤回 CAS 与审核 CAS 均以 status=1 为前置条件——DB 行锁天然互斥，后到者 affected=0。
 * 撤回成功后（status=0），审核 result 的 2→1 恢复因 status≠2 天然 no-op，无需额外防护。
 *
 * 【调用方纪律】affected=0 时不得向用户暴露具体原因（已被撤回/不存在/无权限），统一按
 * 撤回失败处理，防消息存在性探测。
 *
 * 【挂起说明】litisle-social 模块未开发，撤回端点与发送链路嵌入全部挂起（契约实现已落）；
 * 触发条件同 3-5，social 模块正式开发时随实体层一并落地。
 *
 * @author litisle
 */
public final class MessageRecallGate {

    private MessageRecallGate() {
    }

    /**
     * 私信撤回 CAS：仅本人、仅正常态 status=1→0，不物理删正文（4-6.1）。
     *
     * @param messageId 消息 ID
     * @param senderId  当前登录用户 ID（必须是消息发送者本人）
     * @param cas       撤回 CAS 适配（宿主注入：执行上述 CAS SQL，返回 affected rows）
     * @return true-撤回成功（affected=1）；false-失败（非本人/非正常态/消息不存在，不区分原因）
     */
    public static boolean recall(long messageId, long senderId, RecallCasAdapter cas) {
        // 步骤1：适配器判空 fail-fast（静默跳过会伪装成撤回成功，属契约误用，宁严勿松）
        if (cas == null) {
            throw new IllegalArgumentException("RecallCasAdapter 为空，禁止静默跳过撤回 CAS");
        }
        // 步骤2：入参 fail-fast（非法 ID 直拦，避免无效打点 DB）
        if (messageId <= 0 || senderId <= 0) {
            throw new IllegalArgumentException("messageId/senderId 必须为正数");
        }
        // 步骤3：执行 CAS（三条件口径在适配器 SQL 内收口：id + sender_id + status=1）；
        // affected=0 统一失败，调用方禁区分原因（防消息存在性探测，见类注调用方纪律）
        return cas.recallCas(messageId, senderId) == 1;
    }

    /**
     * 撤回 CAS 适配（宿主注入，契约层无 DB 依赖）。
     *
     * 这是契约层定义的一个函数式接口（FunctionalInterface），用来把「真正执行撤回 SQL 的能力」从审核契约代码里剥离出去，让契约层不依赖任何数据库框架。
     * RecallCasAdapter 是“撤回 CAS SQL 的执行入口抽象”——契约层只定义「要做什么」，具体「怎么连数据库」由宿主服务注入实现。
     */
    @FunctionalInterface
    public interface RecallCasAdapter {

        /**
         * 执行撤回 CAS SQL（口径见类注）：update message set status=0
         * where id=? and sender_id=? and status=1。
         *
         * @param messageId 消息 ID
         * @param senderId  发送者 ID（CAS 条件之一，非调用方可伪造入参）
         * @return affected rows（1-成功 / 0-前置条件不满足）
         */
        int recallCas(long messageId, long senderId);
    }
}
