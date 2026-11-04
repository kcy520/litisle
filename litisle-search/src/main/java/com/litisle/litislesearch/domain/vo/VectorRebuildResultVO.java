package com.litisle.litislesearch.domain.vo;

import lombok.Data;

/**
 * 向量全量重建结果（批次七管理端点返回体）。
 *
 * @author litisle
 */
@Data
public class VectorRebuildResultVO {

    /** article_idx 成功更新条数 */
    private int articleUpdated;

    /** article_chunks 成功更新条数 */
    private int chunkUpdated;
}
