package com.litisle.litislecontent.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * 推荐流屏蔽名单（批次十一 11.11，按用户+流加载）
 *
 * <p>同时作为 Redis 名单缓存的存储载体：缓存键按「流 + 用户」隔离，
 * 写入/撤销后只失效该用户该流的键，不做全量 evict。</p>
 *
 * @author litisle
 */
@Data
public class FeedFeedbackExclusions implements Serializable {

    /** 序列化版本号（Redis 缓存反序列化兼容标识） */
    private static final long serialVersionUID = 1L;

    /** 被「不感兴趣」单条屏蔽的内容ID集合（建池与切片都会过滤） */
    private Set<Long> contentIds = new HashSet<>();

    /** 被屏蔽的作者ID集合（只在建池时过滤，旧快照已加载的页不再回查作者） */
    private Set<Long> authorIds = new HashSet<>();

    /** 被屏蔽的分类名集合（只在建池时过滤） */
    private Set<String> categories = new HashSet<>();

    /**
     * 是否为空名单（三集合全空表示无需任何过滤，调用方可直接跳过筛选逻辑）。
     *
     * @return true=无任何屏蔽项
     */
    public boolean isEmpty() {
        return contentIds.isEmpty() && authorIds.isEmpty() && categories.isEmpty();
    }
}
