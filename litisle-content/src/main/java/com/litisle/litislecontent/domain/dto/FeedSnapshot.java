package com.litisle.litislecontent.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐流快照（批次十 10.3：一次打分排序的结果固定落 Redis，翻页只做内存切片）
 *
 * <p>为什么落快照：无限刷过程中数据会变（新发布/下架/热度变化），若不固定顺序，
 * 用户翻页会出现重复或遗漏；快照让「同一个 snapshotId 内顺序恒定」。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 快照代次（字符串形式，Redis INCR 得到，单调递增；前端原样回传用于续刷） */
    private String snapshotId;

    /** 本次快照使用的会话随机种子（同一快照内稳定，仅用于池内探索位打散） */
    private long seed;

    /** 已排好序的条目列表（长度不超过配置的 pool-size） */
    private List<FeedSnapshotItem> items;
}
