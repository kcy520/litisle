package com.litisle.litislesearch.service.support;

import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.service.VectorService;
import com.litisle.litislesearch.utils.ArticleVectorPooler;
import com.litisle.litislesearch.utils.SearchTextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 文章向量池化编排组件（search 板块一 1.1，写入与重建共用单点）。
 *
 * 【职责边界】本类负责「文章向量怎么算」的编排与兜底决策：切片向量在位走池化口径
 * （标题/摘要分别向量化 + 切片聚合 + 加权池化），切片向量缺席走旧口径兜底直算；
 * 聚合数学在 {@link ArticleVectorPooler} 纯函数，Embedding 调用在 {@link VectorService}。
 * 调用方（发布写入 / 元数据更新 / 全量重建）只传素材，不做任何向量口径判断，
 * 保证两链路口径由本类单点约束、防漂移。
 * 【失败语义】Embedding 未就绪或调用失败时返回 null，调用方跳过向量字段写入
 * （字段缺省不影响其余检索字段；存量偏差由全量重建收敛）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleVectorComposer {

    /** 向量化服务（标题/摘要/兜底直算的 Embedding 统一入口） */
    private final VectorService vectorService;

    /** search 配置（池化策略 litisle.search.embedding.pool.strategy） */
    private final SearchProperties properties;

    /**
     * 组装文章向量（池化口径，写入与重建共用）。
     *
     * @param title        文章标题（可空）
     * @param summary      摘要（可空；缺席时其权重并入标题）
     * @param plainText    正文纯文本（可空；兜底直算时按输入上限截断）
     * @param chunkVectors 该篇全部切片向量（可空/空列表=无切片，走兜底直算）
     * @return L2 归一化文章向量；Embedding 未就绪/调用失败返回 null（调用方跳过向量写入）
     */
    public List<Float> compose(String title, String summary, String plainText, List<List<Float>> chunkVectors) {
        /*
         * 业务逻辑步骤：
         * 1. 切片向量缺席（短文未入切片库/纯图/Embedding 未产出）→ 兜底直算：
         *    无正文回退标题向量，其余走 buildEmbeddingInput 旧口径（2000 上限为兜底路径上限）；
         * 2. 切片在位 → 标题/摘要分别向量化（池化三路输入），摘要为空不调 Embedding（权重并入标题）；
         * 3. 切片向量按配置策略聚合（mean/max-pool）→ 加权池化 + L2 归一化输出。
         */
        // 步骤1：无切片兜底——判定按切片向量是否存在（与重建回查口径天然一致），不按正文长度判定
        if (chunkVectors == null || chunkVectors.isEmpty()) {
            // 纯图/无正文：回退标题向量（防 null 向量，清单定案口径）
            if (!StringUtils.hasText(plainText)) {
                return vectorService.embed(title == null ? "" : title);
            }
            // 短文兜底：退化为旧口径直算（buildEmbeddingInput 与全量重建共用同一拼接单点）
            return vectorService.embed(SearchTextUtils.buildEmbeddingInput(title, summary, plainText));
        }
        // 步骤2：标题向量（池化必有权重来源；embed 失败返回 null 由池化器把标题权重归零）
        List<Float> titleVector = vectorService.embed(title == null ? "" : title);
        // 摘要向量：摘要非空才向量化（空摘要不调 Embedding，池化器自动并入标题权重）
        List<Float> summaryVector = StringUtils.hasText(summary) ? vectorService.embed(summary) : null;
        // 步骤3：切片聚合——策略取外置配置（非法值在池化器内按 mean 兜底）
        String strategy = properties.getEmbedding().getPool().getStrategy();
        List<Float> chunkAggregate = ArticleVectorPooler.chunkAggregate(chunkVectors, strategy);
        // 加权池化 + L2 归一化输出（全缺/零模长返回 null，调用方跳过向量写入）
        return ArticleVectorPooler.pool(titleVector, summaryVector, chunkAggregate);
    }
}
