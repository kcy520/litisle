package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.provider.embedding.EmbeddingProvider;
import com.litisle.litisleai.rag.ArticleChunkDoc;
import com.litisle.litisleai.rag.ChunkIndexClient;
import com.litisle.litisleai.rag.ContentChunker;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 摄入编排服务（板块四 4.1：content.published/unpublished → 门控回查 → 切片 → 向量化 → 索引写入）
 *
 * <p>【职责边界】本类收口摄入链路业务编排：MQ 消费者只做 ACK/重试模板；
 * 事件体只携带定位键（bizType/bizId），正文与可见性一律经 ContentClient 回查权威值
 * （P8 定档：正文经 ContentClient 回查，防事件体被篡改或字段过期）。</p>
 *
 * <p>【门控口径】仅摄入 status=2（已发布）且 visibility=1（公开）的文章（未公开内容不外发，
 * 与 D2/V3 出站红线同口径；消费端门控回查为准，防乱序事件误摄入）；
 * 门控不通过按「删除处理」幂等清理既有切片（防转私密后残留）。</p>
 *
 * <p>【改稿口径（search 批次A A2 定案）】文章改稿只更新元数据不重建切片——AI 不消费
 * content.article-updated；改稿一致性由检索侧版本比对兜底（Q75，见 RagSearchService）。</p>
 *
 * <p>【失败语义】embedding/ES 失败上抛 → MQ 消费侧延迟重投；业务性失败（文章不存在）
 * 抛 BizException → 消费侧 ack 丢弃（防毒丸）。</p>
 *
 * <p>【通俗说明】文章进 AI 知识库的"入库流水线"：文章发布后收到通知（MQ 消息），
 * 先不急着入库——回 content 服务把最新的正文、状态、可见性取来核实（消息体可能
 * 过期或被篡改，只信权威数据源），然后三道关口：① 只有"已发布 + 公开"的文章才
 * 配入库，不合格就当删除处理清掉旧切片（防转私密后内容残留被检索到）；② 正文
 * 清洗切片；③ 每片向量化后覆盖写进索引。改稿不重新入库（切片不变），改稿后的
 * 一致性由检索侧的指纹比对兜底。失败分两类：模型/ES 挂了就抛异常让消息延迟重试；
 * 文章压根不存在就丢弃消息，防止"毒丸"卡死队列。</p>
 *
 * <p>【条件装配】litisle.ai.rag.enabled=false 时本 Bean 缺席（唯一调用方 RagIngestConsumer
 * 同为条件装配 Bean）；依赖的 EmbeddingProvider/ElasticsearchClient 同为条件装配 Bean，
 * 硬注入在开关关闭时会因缺 Bean 阻断启动，故一并条件化。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
public class RagIngestService {

    /** 内容服务客户端（权威数据回查） */
    private final ContentClient contentClient;

    /** 切片器（清洗 + 500/50 滑窗） */
    private final ContentChunker chunker;

    /** 向量通道（P3：Ollama bge-m3；AiRagConfig 装配，与本 Bean 同受 rag.enabled 开关控制，开关开启时必在） */
    private final EmbeddingProvider embeddingProvider;

    /** 切片索引客户端（AI 自管索引唯一读写出口） */
    private final ChunkIndexClient chunkIndexClient;

    /** RAG 配置 */
    private final AiRagProperties ragProperties;

    /**
     * 处理文章发布摄入：回查 → 门控 → 切片 → 向量化 → 覆盖写索引
     *
     * @param articleId 文章 ID（事件体定位键）
     * @throws BizException 文章不存在（业务性失败，消费侧 ack 丢弃）
     * @throws IOException  ES 写入失败（消费侧延迟重投）
     *
     * <p>【通俗说明】发布入库五步：① 回查权威数据（查不到就丢弃消息）；② 公开门控
     * （非公开一律按删除清库，隐私红线）；③ 正文清洗切片（洗完为空就清掉旧切片
     * 收工）；④ 确保索引存在，逐片向量化组装文档；⑤ 先删该篇旧切片再覆盖写入
     * （重复发布天然幂等，旧片段绝不残留）。</p>
     */
    public void handlePublished(Long articleId) throws IOException {
        // ① 权威回查：正文/可见性/指纹一律取 content 侧当前值（事件体只做定位）
        ContentDTO content = fetchArticle(articleId);
        if (content == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文章不存在 articleId=" + articleId);
        }
        // ② 门控：仅公开已发布文章摄入（未公开内容不外发）；门控不过按删除处理（防转私密后残留）
        if (!isPublic(content)) {
            log.info("文章非公开态，按删除清理既有切片 articleId={} status={} visibility={}",
                    articleId, content.getStatus(), content.getVisibility());
            chunkIndexClient.deleteByArticle(articleId);
            return;
        }
        // ③ 切片：正文清洗 + 500/50 滑窗
        List<String> chunks = chunker.chunk(content.getBody());
        if (chunks.isEmpty()) {
            log.warn("文章正文为空，无切片可摄入 articleId={}", articleId);
            chunkIndexClient.deleteByArticle(articleId);
            return;
        }
        // ④ 索引就绪 + 逐片向量化组装文档（chunkId=articleId_index，覆盖写幂等）
        chunkIndexClient.ensureIndex();
        LocalDateTime now = LocalDateTime.now();
        List<ArticleChunkDoc> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(buildChunkDoc(content, chunks.get(i), i, now));
        }
        // ⑤ 覆盖写（同篇重复发布幂等覆盖；先按篇删除旧切片防旧片段残留）
        chunkIndexClient.deleteByArticle(articleId);
        chunkIndexClient.bulkUpsert(docs);
        log.info("文章摄入完成 articleId={} chunks={}", articleId, docs.size());
    }

    /**
     * 处理文章下架清理：按篇删除全部切片（隐私红线：不区分下架原因一律删除；幂等空操作）
     *
     * @param articleId 文章 ID
     * @throws IOException ES 访问失败上抛（消费侧延迟重投，保证最终删除）
     *
     * <p>【通俗说明】文章下架（删文/转私密）时把它的所有切片一次清干净——
     * 隐私红线，不管什么原因下架都删；删不到也不报错，天然幂等。</p>
     */
    public void handleUnpublished(Long articleId) throws IOException {
        chunkIndexClient.deleteByArticle(articleId);
        log.info("文章下架切片清理完成 articleId={}", articleId);
    }

    /**
     * 回查文章权威数据（content 侧回查，正文不落日志）
     *
     * @param articleId 文章 ID
     * @return 内容 DTO；不存在/回查失败返回 null（转删除清理路径）
     */
    private ContentDTO fetchArticle(Long articleId) {
        try {
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return null;
            }
            // 日志纪律：只记长度，不打印正文
            log.info("摄入回查成功 articleId={} bodyChars={} visibility={}",
                    articleId,
                    resp.getData().getBody() == null ? 0 : resp.getData().getBody().length(),
                    resp.getData().getVisibility());
            return resp.getData();
        } catch (Exception e) {
            log.warn("摄入回查失败 articleId={}", articleId, e);
            return null;
        }
    }

    /**
     * 公开门控：status=2（已发布）且 visibility=1（公开）——消费端门控回查口径
     * （ContentPublishedEvent 契约注释明示），与 D2/V3 出站红线同口径：未公开内容不外发
     */
    private boolean isPublic(ContentDTO content) {
        return content.getStatus() != null
                && content.getStatus() == AiContentConstant.STATUS_PUBLISHED
                && content.getVisibility() != null
                && content.getVisibility() == AiContentConstant.VISIBILITY_PUBLIC;
    }

    /**
     * 组装切片索引文档（逐片向量化；embedding 失败上抛转消费重试）
     */
    private ArticleChunkDoc buildChunkDoc(ContentDTO content, String chunkText, int index, LocalDateTime now) {
        ArticleChunkDoc doc = new ArticleChunkDoc();
        doc.setChunkId(content.getBizId() + "_" + index);
        doc.setArticleId(content.getBizId());
        doc.setChunkIndex(index);
        doc.setTitle(content.getTitle());
        doc.setAuthorId(content.getOwnerId());
        doc.setChunkText(chunkText);
        doc.setStatus(content.getStatus());
        doc.setVisibility(content.getVisibility());
        doc.setContentHash(content.getContentHash());
        doc.setIngestTime(now);
        doc.setContentVector(embeddingProvider.embed(chunkText));
        return doc;
    }
}
