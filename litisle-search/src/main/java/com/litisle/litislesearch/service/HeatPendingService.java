package com.litisle.litislesearch.service;

import java.util.List;
import java.util.Map;

/**
 * 互动热度待刷新键服务（批次三 3.1 累加 + 3.2 取走/回灌）。
 *
 * 【职责】把点赞 / 评论 / 浏览量三类互动信号累加到 Redis 热度待刷新键（Hash 结构），
 * 不直接写 ES —— 带 1024 维向量的整文档重写写放大约 4KB，按窗口聚合后再由定时任务刷写；
 * 键与字段命名收口在 {@code SearchBizConstant.HEAT_PENDING_KEY_PREFIX / HEAT_PENDING_FIELD_*}。
 *
 * 【幂等口径】
 * - 点赞 / 评论：Hash 自增不可重入，幂等由消费端按 eventId 去重保证（见 HeatEventConsumer）
 * - 浏览量：Hash 覆盖写绝对值，可重放；多条事件按到达顺序覆盖，结果与顺序无关
 *
 * 【异常口径】Redis 访问失败抛非 BizException（IllegalStateException），
 * 由消费端延迟重试，保证「累加成功才 ACK」，防热度漂移。
 *
 * @author litisle
 */
public interface HeatPendingService {

    /**
     * 点赞增量 +1（累加到待刷新键的 like_delta 字段）。
     *
     * @param articleId 被点赞文章 ID
     */
    void incrementLike(Long articleId);

    /**
     * 评论增量 +1（累加到待刷新键的 comment_delta 字段）。
     *
     * @param articleId 被评论文章 ID
     */
    void incrementComment(Long articleId);

    /**
     * 覆盖写入最新浏览量绝对值（待刷新键的 view_cnt 字段）。
     *
     * @param articleId 文章 ID
     * @param viewCount 落库后的权威浏览量
     */
    void overwriteViewCount(Long articleId, Long viewCount);

    /**
     * 扫描全部热度待刷新键（SCAN 游标遍历，禁用 KEYS 阻塞 Redis）。
     *
     * @return 待刷新键列表（命令前缀 + 文章 ID；为空表示本窗口无互动）
     */
    List<String> scanPendingKeys();

    /**
     * 原子取走单个待刷新键（GETDEL 语义）并返回其 Hash 内容。
     *
     * <p>注意：Redis 原生 GETDEL 仅支持 String 类型，本实现键为 Hash，故用 Lua 脚本
     * 「HGETALL + DEL」在同一原子步骤内完成，避免「读出后删除前」被新事件写入而丢增量。</p>
     *
     * @param key 待刷新键（scanPendingKeys 返回值）
     * @return Hash 字段名→值（键不存在或已空时返回空 Map，调用方跳过即可）
     */
    Map<String, String> takePending(String key);

    /**
     * 刷写失败回灌：把已取出但未成功写入 ES 的增量合并回待刷新键，等下一轮重试。
     *
     * <p>点赞/评论按增量追加（不覆盖失败窗口内新到达的增量）；
     * 浏览量按「存在则保留」写入（失败值通常旧于新事件值，避免用旧值覆盖新值）。</p>
     *
     * @param articleId    文章 ID
     * @param likeDelta    点赞增量（0 表示无）
     * @param commentDelta 评论增量（0 表示无）
     * @param viewCount    浏览量绝对值（null 表示本条无浏览量）
     */
    void mergeBack(Long articleId, long likeDelta, long commentDelta, Long viewCount);
}
