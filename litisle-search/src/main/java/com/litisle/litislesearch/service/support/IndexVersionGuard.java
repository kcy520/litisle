package com.litisle.litislesearch.service.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 索引写入版本守卫（企业优化清单 2.1）。
 *
 * 【要解决的问题】MQ 可能重复/乱序投递：同一条内容的新旧事件乱序到达时，
 * 旧快照若后写会把新快照覆盖回去（例如「标题已改」的旧事件迟到，索引标题回退）。
 *
 * 【判定口径】写入前读取 ES 现有文档的 updateTime：
 * - 现有版本 >= 新快照版本（相等也视为过期，幂等跳过）→ 跳过写入；
 * - 现有文档不存在 / 现有 updateTime 缺失 / 新快照 updateTime 缺失 → 无法比较，照常写入
 *   （宁可多写一次幂等覆盖，也不能因版本元数据缺失导致漏同步）。
 *
 * 【失败语义】读取 ES 失败抛 IllegalStateException（可恢复故障），由 MQ 消费重试处理；
 * 绝不在版本未知时盲目写入旧快照。
 *
 * 【适用范围】article_idx / book_idx / user_idx 三索引共用同一套比较口径，避免三处实现漂移。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexVersionGuard {

    /** ES 客户端（只按主键读取 updateTime 字段，sourceIncludes 限制返回体积） */
    private final ElasticsearchClient elasticsearchClient;

    /**
     * 判断新快照是否已过期（过期则调用方跳过写入）。
     *
     * @param index             索引名
     * @param id                文档主键
     * @param newUpdateTime     新快照的版本时间（上游 updateTime/publishTime，可能为 null）
     * @param docType           索引文档类型（决定 ES 反序列化目标）
     * @param updateTimeGetter  从索引文档中取版本时间的方法引用
     * @param <T>               索引文档类型
     * @return true=现有文档版本不落后于新快照，应跳过写入；false=应写入
     */
    public <T> boolean isStale(String index, String id, LocalDateTime newUpdateTime,
                               Class<T> docType, Function<T, LocalDateTime> updateTimeGetter) {
        // 新快照没有版本时间：无法比较，不跳过（保证至少完成一次同步）
        if (newUpdateTime == null) {
            return false;
        }
        GetResponse<T> response;
        try {
            // 按主键读取，只取 updateTime 字段，避免拉回大字段（content 正文等）
            response = elasticsearchClient.get(g -> g
                    .index(index)
                    .id(id)
                    .sourceIncludes(SearchBizConstant.FIELD_UPDATE_TIME), docType);
        } catch (Exception e) {
            // 读取失败属于可恢复故障：抛出让 MQ 重试，避免在版本未知时用旧快照覆盖
            throw new IllegalStateException(
                    "读取索引文档版本失败，稍后重试：index=" + index + ", id=" + id, e);
        }
        // 文档不存在（首次写入）或没有 _source：无法比较，照常写入
        if (!response.found() || response.source() == null) {
            return false;
        }
        LocalDateTime currentUpdateTime = updateTimeGetter.apply(response.source());
        // 现有文档缺少版本时间（历史脏数据）：无法比较，照常写入以补齐版本字段
        if (currentUpdateTime == null) {
            return false;
        }
        // 相等也跳过：同一版本重复投递按幂等处理，避免重复写入与重复重建切片
        boolean stale = !currentUpdateTime.isBefore(newUpdateTime);
        if (stale) {
            log.info("[索引同步] 版本未前进，跳过写入：index={}, id={}, current={}, incoming={}",
                    index, id, currentUpdateTime, newUpdateTime);
        }
        return stale;
    }
}
