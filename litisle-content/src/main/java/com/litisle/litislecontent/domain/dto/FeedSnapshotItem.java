package com.litisle.litislecontent.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 快照条目（批次十 10.3：只存「对象 ID + 融合分数」）
 *
 * <p>不存整条 VO：卡片详情在切片时按 ID 回查数据库，可获得最新的标题/计数，
 * 同时保证下架/转私密的旧条目在展示前被门控过滤掉。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedSnapshotItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 对象 ID（文章流为 article.id，书籍流为 book.id） */
    private Long id;

    /** 融合分数（仅用于调试与排序复现，切片本身按列表顺序，不重新排序） */
    private double score;
}
