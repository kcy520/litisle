package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.ai.AiLabelRequestDTO;
import com.litisle.litisleapi.dto.ai.AiLabelResponseDTO;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.feign.AiClient;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchGateConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.domain.entity.ArticleMetaDoc;
import com.litisle.litislesearch.enums.SearchTypeEnum;
import com.litisle.litislesearch.service.ArticleChunkService;
import com.litisle.litislesearch.service.ArticleIndexService;
import com.litisle.litislesearch.service.ArticleTombstoneService;
import com.litisle.litislesearch.service.support.ArticleVectorComposer;
import com.litisle.litislesearch.service.support.IndexVersionGuard;
import com.litisle.litislesearch.utils.SearchTextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** RAG 切片服务（文章发布/改稿时重建切片并取回切片向量，供池化聚合） */
    private final ArticleChunkService articleChunkService;

    /** 文章向量池化编排组件（search 板块一 1.1/1.3：写入与重建共用的向量口径单点） */
    private final ArticleVectorComposer articleVectorComposer;

    /** 索引版本守卫（企业优化清单 2.1：旧快照迟到时跳过写入，防覆盖新快照） */
    private final IndexVersionGuard indexVersionGuard;

    /** 文章下架墓碑（批次九 P0-D：跨队列发布/下架竞态的隐私兜底） */
    private final ArticleTombstoneService articleTombstoneService;

    /** AI 服务客户端（板块八/search 板块二 2.2：索引期打标 /api/feign/ai/label） */
    private final AiClient aiClient;

    /**
     * 处理文章发布：回查 → 门控 → 重建切片（取回切片向量）→ 池化聚合 vec_dense → upsert 索引文档。
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
        // 步骤2.5：下架墓碑兜底（隐私红线）——命中说明该文章在下架重投窗口内被清理过；
        // 旧发布事件可能带着「下架前」的快照迟到，此处强制重新回查权威态：当前仍非公开则丢弃，
        // 已重新公开则改用最新快照继续（不阻塞合法的二次发布）
        if (articleTombstoneService.exists(articleId)) {
            ArticleIndexDocDTO fresh = fetchArticle(articleId);
            if (!isPublicPublished(fresh)) {
                log.info("[索引同步] 命中下架墓碑且当前非公开，跳过发布写入：articleId={}, status={}, visibility={}",
                        articleId, fresh.getStatus(), fresh.getVisibility());
                return;
            }
            dto = fresh;
            log.info("[索引同步] 命中下架墓碑但文章已重新公开，改用最新快照写入：articleId={}", articleId);
        }
        // 步骤3：版本比较——旧快照迟到（现有 updateTime 不早于本次）时跳过整篇写入与切片重建，
        // 防旧标题/旧正文覆盖新内容（企业优化清单 2.1；等版本按幂等跳过）
        if (isStaleVersion(articleId, dto.getPublishTime())) {
            return;
        }
        // 步骤4：组装索引文档基础字段（向量延后：先保切片向量在位再池化，search 板块一 1.3）
        ArticleIndexDoc doc = buildBaseDoc(dto);
        // 步骤5：重建 RAG 知识库切片并取回切片向量（正文长度门槛在切片服务内判断；
        // 切片先行保证池化时切片向量在位，且切片文档与 vec_dense 同轮写入口径一致）
        List<List<Float>> chunkVectors = articleChunkService.rebuild(doc, doc.getContent());
        // 步骤6：池化聚合写 vec_dense（写入与重建共用 ArticleVectorComposer 单点；Embedding 失败为 null 字段缺省）
        doc.setVecDense(articleVectorComposer.compose(doc.getTitle(), doc.getSummary(), doc.getContent(), chunkVectors));
        // 步骤6.5：正文字数（索引期按正文纯文本纯计算，零 AI 成本）+ ai_labels 维度打标
        //（板块八/search 板块二 2.2；打标失败留空不阻断索引主链路）
        doc.setWordCount((long) doc.getContent().length());
        doc.setAiLabels(labelArticle(dto));
        // 步骤7：写入文章索引文档（按 articleId 幂等 upsert）
        upsertArticleDoc(doc);
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
        // 步骤1.5：写文章下架墓碑（隐私兜底）——跨队列旧发布事件可能在删除后把文档写回，
        // 发布/元数据写路径命中墓碑会强制重新门控；写入失败抛可恢复异常，交 MQ 延迟重投
        // （删除与再打标均幂等，重放无副作用）
        articleTombstoneService.markUnpublished(articleId);
        // 步骤2：删除该文章全部切片（防下架内容继续被 RAG 引用）
        articleChunkService.deleteByArticleId(articleId);
        // 说明：热点查询缓存失效随批次四缓存落地接入（当前无缓存）
        log.info("[索引同步] 文章下架清理完成：articleId={}", articleId);
    }

    /**
     * 处理已发布文章元数据编辑：partial update 元数据字段 + 改稿联动刷新切片与 vec_dense（池化口径）。
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
        // 步骤2.5：下架墓碑兜底——命中则重新回查：仍非公开按清理收敛，已重新公开用新快照继续
        if (articleTombstoneService.exists(articleId)) {
            ArticleIndexDocDTO fresh = fetchArticle(articleId);
            if (!isPublicPublished(fresh)) {
                log.info("[索引同步] 命中下架墓碑且当前非公开，转为清理：articleId={}, status={}, visibility={}",
                        articleId, fresh.getStatus(), fresh.getVisibility());
                handleUnpublished(articleId);
                return;
            }
            dto = fresh;
            log.info("[索引同步] 命中下架墓碑但文章已重新公开，改用最新快照更新元数据：articleId={}", articleId);
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
        // 步骤4.5：改稿重新打标整体覆盖 ai_labels + 重算 word_count（板块八/search 板块二 2.2；
        // 打标失败不写标签字段保持原值，不阻断元数据更新主链路）
        String updatedPlainText = plainTextOf(dto);
        metaDoc.setWordCount((long) updatedPlainText.length());
        metaDoc.setAiLabels(labelArticle(dto));
        // 步骤5：改稿联动（search 板块一 1.3）——切片随最新标题/正文重建（切片文档冗余 title/authorName
        // 刷新、正文未变则 chunk_text 不变）+ vec_dense 池化重算回填 metaDoc，全链路无人工介入；
        // embed 失败为 null 不序列化，保持原 vec_dense 不回退
        refreshSlicesAndVector(articleId, dto, updatedPlainText, metaDoc);
        // 步骤6：partial update（文档不存在说明首发消息丢失，告警并跳过，由后续重发/重建补齐）；
        // 元数据与刷新后的 vec_dense 一次写入
        updateArticleMeta(articleId, metaDoc);
        log.info("[索引同步] 文章元数据更新完成：articleId={}", articleId);
    }

    /**
     * 改稿联动：重建切片并按池化口径刷新 vec_dense（随元数据 partial update 一并写入）。
     *
     * <p>【业务逻辑步骤】
     * 1. 用回查 DTO 组装切片重建所需的基础文档（冗余字段来源，不触发向量/打标计算）；
     * 2. 重建切片取回切片向量（正文未变时 chunk_text 不变，冗余 title/authorName 刷新）；
     * 3. 池化聚合新 vec_dense 写入 metaDoc（embed 失败为 null，不序列化即保持原值）。</p>
     *
     * @param articleId        文章 ID
     * @param dto              回查 DTO（title/summary/contentHtml 等冗余字段来源）
     * @param updatedPlainText 最新正文纯文本
     * @param metaDoc          待写入的元数据局部更新文档（vec_dense 成功时回填到该文档）
     */
    private void refreshSlicesAndVector(Long articleId, ArticleIndexDocDTO dto,
                                        String updatedPlainText, ArticleMetaDoc metaDoc) {
        /*
         * 业务逻辑步骤：
         * 1. 组装切片重建基础文档（只填切片服务需要的冗余字段）；
         * 2. 重建切片并取回切片向量（正文过短未入库时为空列表，编排组件自动走兜底直算）；
         * 3. 池化聚合 vec_dense 回填 metaDoc（失败为 null 不覆盖原值）。
         */
        // 步骤1：切片重建基础文档（复用 buildBaseDoc 的冗余字段组装，避免字段口径漂移）
        ArticleIndexDoc chunkBase = buildBaseDoc(dto);
        // 步骤2：重建切片取回切片向量（先保切片向量在位，与发布链路同一切片服务）
        List<List<Float>> chunkVectors = articleChunkService.rebuild(chunkBase, updatedPlainText);
        // 步骤3：池化聚合新 vec_dense（写入与重建共用 ArticleVectorComposer 单点）
        List<Float> pooled = articleVectorComposer.compose(dto.getTitle(), dto.getSummary(),
                updatedPlainText, chunkVectors);
        // 成功才回填：null 时 metaDoc.vecDense 保持 null，NON_NULL 序列化下不覆盖索引原向量
        if (pooled != null) {
            metaDoc.setVecDense(pooled);
        }
        // 单篇刷新日志（articleId 仅用于排障定位）
        log.debug("[索引同步] 改稿联动向量刷新：articleId={}, pooled={}", articleId, pooled != null);
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
                updateTime, SearchTypeEnum.ARTICLE.getCode(), ArticleIndexDoc.class, ArticleIndexDoc::getUpdateTime);
    }

    /**
     * 判断文章是否满足入库门控：已发布（status=2）且公开（visibility=1）。
     *
     * @param dto 文章回查数据
     * @return true=允许入索引
     */
    private boolean isPublicPublished(ArticleIndexDocDTO dto) {
        return SearchGateConstant.ARTICLE_STATUS_PUBLISHED.equals(dto.getStatus())
                && SearchGateConstant.ARTICLE_VISIBILITY_PUBLIC.equals(dto.getVisibility());
    }

    /**
     * 组装文章索引文档基础字段（字段与 EsIndexInitializer#articleMapping 对齐，不含向量）。
     *
     * <p>search 板块一 1.3 起向量延后计算：先由切片服务重建切片取回切片向量，
     * 再经 ArticleVectorComposer 池化聚合写入，保证切片向量在位后再池化。</p>
     *
     * @param dto 文章回查数据
     * @return 索引文档（vecDense 字段缺省，由调用方池化后回填）
     */
    private ArticleIndexDoc buildBaseDoc(ArticleIndexDocDTO dto) {
        ArticleIndexDoc doc = new ArticleIndexDoc();
        doc.setId(String.valueOf(dto.getArticleId()));
        doc.setTitle(dto.getTitle());
        doc.setTags(dto.getTags());
        doc.setSummary(dto.getSummary());
        // 正文转纯文本后入检索字段（HTML 标签会污染分词）
        String plainText = SearchTextUtils.toPlainText(dto.getContentHtml());
        doc.setContent(plainText);
        // 写入侧可见性闸门：只有 PUBLIC 才写入，值固定为 PUBLIC（查询侧另有 filter 双保险）
        doc.setVisibility(SearchGateConstant.VISIBILITY_PUBLIC);
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
        return doc;
    }

    /**
     * 索引期打标：调 AI /api/feign/ai/label 按维度注册表词表标注 ai_labels
     *
     * <p>【业务逻辑步骤】
     * 1. 打标开关检查（labelingEnabled=false 或 AI 响应异常 → 返回 null，标签留空降级）；
     * 2. 组装打标请求（requestId=articleId 稳定键防重复计费；正文截断到配置上限防 prompt 放大）；
     * 3. 调用打标端点，失败仅告警返回 null（重试语义由本类承担为「留空降级」，不阻断索引主链路）。</p>
     *
     * @param dto 文章回查数据（title/contentHtml 取打标依据）
     * @return 维度标签集（key=维度名）；开关关闭/打标失败返回 null（ai_labels 留空，该维度过滤退化语义兜底）
     */
    private Map<String, String> labelArticle(ArticleIndexDocDTO dto) {
        // ① 打标开关检查：关闭时直接跳过（ai_labels 留空，该维度过滤退化为语义兜底）
        if (!properties.getAiLabels().isLabelingEnabled()) {
            return null;
        }
        // ② 组装打标请求：requestId 用 articleId 稳定键（同文重复打标幂等不重复计费）；
        // 正文截断到配置上限，防超长正文撑爆打标 prompt
        AiLabelRequestDTO request = new AiLabelRequestDTO();
        request.setRequestId("index-label-" + dto.getArticleId());
        request.setTitle(dto.getTitle() == null ? "" : dto.getTitle());
        request.setContent(truncateForLabel(plainTextOf(dto)));
        try {
            // ③ 调用打标端点（fail-fast 契约：预算超限/校验耗尽上抛，此处统一降级留空）
            Result<AiLabelResponseDTO> resp = aiClient.label(request);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.warn("[索引同步] 文章打标响应异常，ai_labels 留空降级：articleId={}, code={}",
                        dto.getArticleId(), resp == null ? "null" : resp.getCode());
                return null;
            }
            Map<String, String> labels = resp.getData().getLabels();
            // 全 null 标签归一为 null（空映射不入文档，保持字段缺省语义一致）
            return labels == null || labels.isEmpty() ? null : labels;
        } catch (Exception e) {
            // 打标失败不阻断索引主链路（铁律）：告警留痕，ai_labels 留空，可经存量补标回填
            log.warn("[索引同步] 文章打标失败，ai_labels 留空降级（不阻断索引）：articleId={}",
                    dto.getArticleId(), e);
            return null;
        }
    }

    /**
     * 取打标用正文纯文本（HTML 去标签；contentHtml 缺失时返回空串）
     *
     * @param dto 文章回查数据
     * @return 正文纯文本
     */
    private String plainTextOf(ArticleIndexDocDTO dto) {
        return dto.getContentHtml() == null ? "" : SearchTextUtils.toPlainText(dto.getContentHtml());
    }

    /**
     * 打标正文截断（配置上限内原样返回，超出截断）
     *
     * @param plainText 正文纯文本
     * @return 截断后文本
     */
    private String truncateForLabel(String plainText) {
        int maxChars = Math.max(1, properties.getAiLabels().getLabelContentMaxChars());
        return plainText.length() > maxChars ? plainText.substring(0, maxChars) : plainText;
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
