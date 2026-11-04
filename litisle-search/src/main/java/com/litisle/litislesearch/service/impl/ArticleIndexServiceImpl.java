package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.domain.entity.ArticleMetaDoc;
import com.litisle.litislesearch.service.ArticleChunkService;
import com.litisle.litislesearch.service.ArticleIndexService;
import com.litisle.litislesearch.service.VectorService;
import com.litisle.litislesearch.service.support.IndexVersionGuard;
import com.litisle.litislesearch.utils.SearchTextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 文章索引同步服务实现（批次二 2.2/2.3）。
 *
 * 核心铁律：
 * 1. 事件体只带 articleId，所有元数据一律 ContentClient 回查权威值（禁信事件体）；
 * 2. 乱序门控：回查后必须 status=2 且 visibility=1(PUBLIC) 才写入，
 *    防 unpublished 先到、published 迟到把已删/转私密内容写回索引；
 * 3. 幂等：写入按 articleId upsert、删除按 articleId delete（404 视为成功）、
 *    元数据编辑走 partial update，重复消费结果一致；
 * 4. 异常分流：业务性失败（文章不存在）抛 BizException 由消费端丢弃防毒丸；
 *    ES/网络等可恢复异常抛非 BizException，由消费端延迟重试。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleIndexServiceImpl implements ArticleIndexService {

    /** ES 客户端（article_idx 读写入口） */
    private final ElasticsearchClient elasticsearchClient;

    /** content 服务客户端（建索引元数据回查） */
    private final ContentClient contentClient;

    /** search 配置（索引名等） */
    private final SearchProperties properties;

    /** RAG 切片服务（文章发布时重建切片、下架时删除切片） */
    private final ArticleChunkService articleChunkService;

    /** 向量化服务（模型未接入时返回 null，向量字段缺省） */
    private final VectorService vectorService;

    /** 索引版本守卫（企业优化清单 2.1：旧快照迟到时跳过写入，防覆盖新快照） */
    private final IndexVersionGuard indexVersionGuard;

    /**
     * 处理文章发布：回查 → 门控 → upsert 索引文档 → 重建切片。
     *
     * @param articleId 文章 ID
     */
    @Override
    public void handlePublished(Long articleId) {
        // 步骤1：回查权威元数据（事件体只有定位键）
        ArticleIndexDocDTO dto = fetchArticle(articleId);
        // 步骤2：乱序/可见性门控（不满足则丢弃，不写索引）
        if (!isPublicPublished(dto)) {
            log.info("[索引同步] 发布门控未通过，跳过写入：articleId={}, status={}, visibility={}",
                    articleId, dto.getStatus(), dto.getVisibility());
            return;
        }
        // 步骤3：版本比较——旧快照迟到（现有 updateTime 不早于本次）时跳过整篇写入与切片重建，
        // 防旧标题/旧正文覆盖新内容（企业优化清单 2.1；等版本按幂等跳过）
        if (isStaleVersion(articleId, dto.getPublishTime())) {
            return;
        }
        // 步骤4：组装并写入文章索引文档（按 articleId 幂等 upsert）
        ArticleIndexDoc doc = buildIndexDoc(dto);
        upsertArticleDoc(doc);
        // 步骤5：重建 RAG 知识库切片（正文长度门槛在切片服务内判断）
        articleChunkService.rebuild(doc, doc.getContent());
        log.info("[索引同步] 文章发布写入完成：articleId={}", articleId);
    }

    /**
     * 处理文章下架：删除索引文档与全部切片（四条下架路径共用）。
     *
     * @param articleId 文章 ID
     */
    @Override
    public void handleUnpublished(Long articleId) {
        // 步骤1：删除文章索引文档（不存在视为成功，幂等）
        deleteArticleDoc(articleId);
        // 步骤2：删除该文章全部切片（防下架内容继续被 RAG 引用）
        articleChunkService.deleteByArticleId(articleId);
        // 说明：热点查询缓存失效随批次四缓存落地接入（当前无缓存）
        log.info("[索引同步] 文章下架清理完成：articleId={}", articleId);
    }

    /**
     * 处理已发布文章元数据编辑：只 partial update 索引元数据字段，不动正文/向量/切片。
     *
     * @param articleId 文章 ID
     */
    @Override
    public void handleArticleUpdated(Long articleId) {
        // 步骤1：回查权威元数据
        ArticleIndexDocDTO dto = fetchArticle(articleId);
        // 步骤2：门控兜底——若文章已不再公开或已下架（乱序/事后下架），按清理处理
        if (!isPublicPublished(dto)) {
            log.warn("[索引同步] 元数据更新时文章已非公开态，转为清理：articleId={}, status={}, visibility={}",
                    articleId, dto.getStatus(), dto.getVisibility());
            handleUnpublished(articleId);
            return;
        }
        // 步骤3：版本比较——旧元数据快照迟到时跳过 partial update，防元数据回退（企业优化清单 2.1）
        if (isStaleVersion(articleId, dto.getPublishTime())) {
            return;
        }
        // 步骤4：组装元数据局部更新文档（null 字段不序列化，避免清空未变更字段）
        ArticleMetaDoc metaDoc = new ArticleMetaDoc();
        metaDoc.setTitle(dto.getTitle());
        metaDoc.setTags(dto.getTags());
        metaDoc.setSummary(dto.getSummary());
        metaDoc.setZone(dto.getZone() == null ? null : String.valueOf(dto.getZone()));
        metaDoc.setCategory(dto.getCategory());
        metaDoc.setUpdateTime(dto.getPublishTime());
        // 步骤5：partial update（文档不存在说明首发消息丢失，告警并跳过，由后续重发/重建补齐）
        updateArticleMeta(articleId, metaDoc);
        log.info("[索引同步] 文章元数据更新完成：articleId={}", articleId);
    }

    /**
     * 回查文章建索引元数据：区分「业务性不存在」与「可恢复故障」。
     *
     * @param articleId 文章 ID
     * @return 文章索引回查 DTO
     */
    private ArticleIndexDocDTO fetchArticle(Long articleId) {
        // 调用 content 内部端点（路径 /api/feign/content/article-index-doc，网关不路由）
        Result<ArticleIndexDocDTO> result = contentClient.getArticleIndexDoc(articleId);
        // 成功分支：取数据
        if (result != null && result.isSuccess() && result.getData() != null) {
            return result.getData();
        }
        int code = result == null ? ErrorCode.REMOTE_SERVICE_ERROR.getCode() : result.getCode();
        // 业务性不存在：文章已删除/未创建，消息无意义 → BizException 由消费端丢弃（防毒丸）
        if (code == ErrorCode.ARTICLE_NOT_FOUND.getCode()) {
            throw new BizException(ErrorCode.SEARCH_SOURCE_NOT_FOUND);
        }
        // 其余（远程服务异常/未知错误）属可恢复故障 → 非 BizException，触发延迟重试
        throw new IllegalStateException("回查文章失败（可重试），articleId=" + articleId + ", code=" + code);
    }

    /**
     * 判断文章快照是否已过期（供发布/元数据更新两条写路径共用）。
     *
     * @param articleId 文章 ID
     * @param updateTime 快照版本时间（回查 DTO 的 publishTime，content 侧即文章 updateTime）
     * @return true=现有索引版本不早于本快照，应跳过写入
     */
    private boolean isStaleVersion(Long articleId, LocalDateTime updateTime) {
        return indexVersionGuard.isStale(properties.getIndex().getArticle(), String.valueOf(articleId),
                updateTime, ArticleIndexDoc.class, ArticleIndexDoc::getUpdateTime);
    }

    /**
     * 判断文章是否满足入库门控：已发布（status=2）且公开（visibility=1）。
     *
     * @param dto 文章回查数据
     * @return true=允许入索引
     */
    private boolean isPublicPublished(ArticleIndexDocDTO dto) {
        return SearchBizConstant.ARTICLE_STATUS_PUBLISHED.equals(dto.getStatus())
                && SearchBizConstant.ARTICLE_VISIBILITY_PUBLIC.equals(dto.getVisibility());
    }

    /**
     * 组装文章索引文档（字段与 EsIndexInitializer#articleMapping 对齐）。
     *
     * @param dto 文章回查数据
     * @return 索引文档
     */
    private ArticleIndexDoc buildIndexDoc(ArticleIndexDocDTO dto) {
        ArticleIndexDoc doc = new ArticleIndexDoc();
        doc.setId(String.valueOf(dto.getArticleId()));
        doc.setTitle(dto.getTitle());
        doc.setTags(dto.getTags());
        doc.setSummary(dto.getSummary());
        // 正文转纯文本后入检索字段（HTML 标签会污染分词）
        String plainText = SearchTextUtils.toPlainText(dto.getContentHtml());
        doc.setContent(plainText);
        // 写入侧可见性闸门：只有 PUBLIC 才写入，值固定为 PUBLIC（查询侧另有 filter 双保险）
        doc.setVisibility(SearchBizConstant.VISIBILITY_PUBLIC);
        doc.setAuthorId(dto.getAuthorId() == null ? null : String.valueOf(dto.getAuthorId()));
        doc.setAuthorName(dto.getAuthorName());
        doc.setZone(dto.getZone() == null ? null : String.valueOf(dto.getZone()));
        doc.setCategory(dto.getCategory());
        doc.setLikeCnt(dto.getLikeCnt());
        doc.setCommentCnt(dto.getCommentCnt());
        doc.setViewCnt(dto.getViewCnt());
        // 发布瞬间互动计数为 0，热度初始 0；批次三 IndexHeatFlushJob 按 A5 公式重算
        doc.setHeat(0.0D);
        doc.setUpdateTime(dto.getPublishTime());
        // 向量预计算（模型未接入返回 null → 字段缺省，不影响其余检索字段）
        doc.setVecDense(embedArticle(dto, plainText));
        return doc;
    }

    /**
     * 计算文章向量（标题 + 摘要 + 正文截断片段，控制模型输入长度）。
     *
     * @param dto       文章回查数据
     * @param plainText 正文纯文本
     * @return 向量；模型未接入返回 null
     */
    private java.util.List<Float> embedArticle(ArticleIndexDocDTO dto, String plainText) {
        // 拼接语义输入：标题权重最高，其次摘要与正文；与全量重建共用同一规则（SearchTextUtils）
        String input = SearchTextUtils.buildEmbeddingInput(dto.getTitle(), dto.getSummary(), plainText);
        return vectorService.embed(input);
    }

    /**
     * 写入（upsert）文章索引文档。
     *
     * @param doc 索引文档
     */
    private void upsertArticleDoc(ArticleIndexDoc doc) {
        String articleIndex = properties.getIndex().getArticle();
        try {
            // 按 id=articleId 写入：文档已存在则整篇覆盖（发布事件即权威全量）
            elasticsearchClient.index(i -> i.index(articleIndex).id(doc.getId()).document(doc));
        } catch (Exception e) {
            // ES 异常包装为非 BizException，触发消费端延迟重试
            throw new IllegalStateException("写入文章索引失败（ES 异常，稍后重试），articleId=" + doc.getId(), e);
        }
    }

    /**
     * 删除文章索引文档（不存在视为成功，幂等）。
     *
     * @param articleId 文章 ID
     */
    private void deleteArticleDoc(Long articleId) {
        String articleIndex = properties.getIndex().getArticle();
        String id = String.valueOf(articleId);
        try {
            // 先判断存在性：ES delete 对不存在的文档返回 404，此处视为幂等成功，避免误判为异常
            boolean exists = elasticsearchClient.exists(e -> e.index(articleIndex).id(id)).value();
            if (!exists) {
                log.debug("[索引同步] 待删除文章索引文档不存在（幂等跳过）：articleId={}", articleId);
                return;
            }
            elasticsearchClient.delete(d -> d.index(articleIndex).id(id));
        } catch (Exception e) {
            // 删除失败必须重试：违规/下架内容残留索引属隐私红线
            throw new IllegalStateException("删除文章索引失败（ES 异常，稍后重试），articleId=" + articleId, e);
        }
    }

    /**
     * 局部更新文章索引元数据（只改传入字段）。
     *
     * @param articleId 文章 ID
     * @param metaDoc   元数据文档（null 字段不序列化）
     */
    private void updateArticleMeta(Long articleId, ArticleMetaDoc metaDoc) {
        String articleIndex = properties.getIndex().getArticle();
        String id = String.valueOf(articleId);
        try {
            // 文档不存在（首发消息可能丢失）：告警跳过，避免 update 抛 404 触发无意义重试
            boolean exists = elasticsearchClient.exists(e -> e.index(articleIndex).id(id)).value();
            if (!exists) {
                log.warn("[索引同步] 元数据更新跳过：索引文档不存在（需重发 published 或重建），articleId={}", articleId);
                return;
            }
            // update + doc：只覆盖给出的字段，不动 content/vec_dense；
            // 第二个参数显式指定目标文档类型，供泛型推断 TDocument（部分更新类型由 metaDoc 推断）
            elasticsearchClient.update(u -> u.index(articleIndex).id(id).doc(metaDoc), ArticleIndexDoc.class);
        } catch (Exception e) {
            throw new IllegalStateException("更新文章索引元数据失败（ES 异常，稍后重试），articleId=" + articleId, e);
        }
    }
}
