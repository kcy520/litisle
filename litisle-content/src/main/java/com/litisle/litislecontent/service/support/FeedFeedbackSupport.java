package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedFeedbackExclusions;
import com.litisle.litislecontent.domain.entity.FeedFeedback;
import com.litisle.litislecontent.mapper.FeedFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 推荐流屏蔽名单读取组件（批次十一 11.11）
 *
 * <p>作用：按「用户 + 内容流」加载最近 N 天（默认 30 天）的屏蔽名单，
 * 供建池阶段过滤候选、切片阶段过滤单条内容。名单带 Redis 缓存（单用户单键），
 * 写入/撤销后由服务层按用户键失效，不做全量 evict。</p>
 *
 * <p>匿名用户没有稳定身份，不参与负反馈：userId 为空直接返回空名单。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFeedbackSupport {

    /** 负反馈 Mapper（按用户+时间窗读取名单） */
    private final FeedFeedbackMapper feedFeedbackMapper;

    /** Redis 工具（名单缓存读写与按用户失效） */
    private final RedisUtil redisUtil;

    /** content 配置（屏蔽时间窗与缓存 TTL） */
    private final ContentProperties contentProperties;

    /**
     * 加载某用户在某内容流下的屏蔽名单。
     *
     * @param userId 登录用户ID（匿名传 null，直接返回空名单）
     * @param stream 内容流（article/book）
     * @return 屏蔽名单（不返回 null；空名单也可直接跳过过滤）
     */
    public FeedFeedbackExclusions loadExclusions(Long userId, String stream) {
        // 匿名无稳定身份：不做长期屏蔽，直接返回空名单（取舍已写入文档）
        if (userId == null || !StringUtils.hasText(stream)) {
            return new FeedFeedbackExclusions();
        }
        // 步骤1：读缓存（只接受本类写入的载体类型，旧格式/脏值按未命中）
        String cacheKey = buildCacheKey(stream, userId);
        Object cached = redisUtil.get(cacheKey);
        if (cached instanceof FeedFeedbackExclusions exclusions) {
            return exclusions;
        }
        // 步骤2：回源 DB（只取时间窗内的记录）
        FeedFeedbackExclusions loaded = loadFromDb(userId, stream);
        // 步骤3：回填缓存（空名单也缓存，防同一用户反复穿透）
        writeCache(cacheKey, loaded);
        return loaded;
    }

    /**
     * 失效某用户在某内容流下的名单缓存（写入/撤销后调用）。
     *
     * <p>只删该用户该流的键，不触碰其他用户，也不清空快照。</p>
     *
     * @param userId 登录用户ID
     * @param stream 内容流（article/book）
     */
    public void invalidate(Long userId, String stream) {
        // 参数缺失时无键可删，直接返回
        if (userId == null || !StringUtils.hasText(stream)) {
            return;
        }
        redisUtil.delete(buildCacheKey(stream, userId));
    }

    /**
     * 从 DB 读取时间窗内的名单并归类。
     *
     * @param userId 登录用户ID
     * @param stream 内容流
     * @return 三类屏蔽集合
     */
    private FeedFeedbackExclusions loadFromDb(Long userId, String stream) {
        // 屏蔽时间窗（天）：超过窗口的记录自动失效，避免永久屏蔽把内容池越刷越窄
        int windowDays = contentProperties.getFeed().getFeedback().getWindowDays();
        // 窗口起点
        LocalDateTime windowStart = LocalDateTime.now().minusDays(windowDays);

        // 查询条件：当前用户 + 当前流 + 时间窗内（列名走 Lambda，避免硬编码字段名）
        LambdaQueryWrapper<FeedFeedback> wrapper = new LambdaQueryWrapper<FeedFeedback>()
                .eq(FeedFeedback::getUserId, userId)
                .eq(FeedFeedback::getStream, stream)
                .ge(FeedFeedback::getCreateTime, windowStart);
        // 时间窗内的屏蔽记录
        List<FeedFeedback> rows = feedFeedbackMapper.selectList(wrapper);

        // 按类型归类到三个集合
        FeedFeedbackExclusions exclusions = new FeedFeedbackExclusions();
        for (FeedFeedback row : rows) {
            // 类型可能来自历史脏数据，未知类型直接忽略
            Integer type = row.getFeedbackType();
            if (type == null) {
                continue;
            }
            switch (type) {
                case ContentBizConstant.FEEDBACK_TYPE_CONTENT -> exclusions.getContentIds().add(row.getTargetId());
                case ContentBizConstant.FEEDBACK_TYPE_AUTHOR -> exclusions.getAuthorIds().add(row.getTargetId());
                case ContentBizConstant.FEEDBACK_TYPE_CATEGORY -> exclusions.getCategories().add(row.getTargetKey());
                default -> log.debug("忽略未知负反馈类型: type={}, id={}", type, row.getId());
            }
        }
        return exclusions;
    }

    /**
     * 写入名单缓存。
     *
     * @param cacheKey 缓存键
     * @param exclusions 名单
     */
    private void writeCache(String cacheKey, FeedFeedbackExclusions exclusions) {
        // TTL 配置化：非正数表示关闭缓存
        long ttlSeconds = contentProperties.getFeed().getFeedback().getCacheTtlSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        // Redis 异常由 RedisUtil 吞掉，名单加载不因缓存失败中断
        redisUtil.set(cacheKey, exclusions, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 生成名单缓存键（按流 + 用户隔离）。
     *
     * @param stream 内容流
     * @param userId 登录用户ID
     * @return 缓存键
     */
    private String buildCacheKey(String stream, Long userId) {
        return ContentBizConstant.FEED_FEEDBACK_CACHE_KEY_PREFIX
                + stream + ":" + FeedConstant.IDENTITY_USER_PREFIX + userId;
    }
}
