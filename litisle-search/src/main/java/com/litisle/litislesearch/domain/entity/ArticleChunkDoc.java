package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章正文切片文档（article_chunks 的 ES 文档映射实体，RAG 知识库）。
 *
 * 【说明】search 侧负责摄入（发布时切片写入），ai 侧只查询；
 * 一期仅公开过审且正文 ≥200 字的文章入库，书籍章节不入库（批次C C2 定版）。
 * 【字段口径】chunkId 形如 "articleId_序号"；chunkVector 在 Embedding 提供方接入前缺省。
 * 【命名说明】Java 字段用驼峰，@JsonProperty 引用常量绑定 ES 下划线字段名。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleChunkDoc {

    /** 切片主键：articleId + 分隔符 + 序号 */
    @JsonProperty(SearchBizConstant.FIELD_CHUNK_ID)
    private String chunkId;

    /** 所属文章 ID（按 articleId 幂等覆盖删旧写新） */
    @JsonProperty(SearchBizConstant.FIELD_ARTICLE_ID)
    private Long articleId;

    /** 作者 ID（RAG 检索可选过滤） */
    @JsonProperty(SearchBizConstant.FIELD_AUTHOR_ID)
    private String authorId;

    /** 文章标题（冗余，引用卡片展示） */
    @JsonProperty(SearchBizConstant.FIELD_TITLE)
    private String title;

    /** 作者昵称（冗余，引用卡片展示） */
    @JsonProperty(SearchBizConstant.FIELD_AUTHOR_NAME)
    private String authorName;

    /** 切片正文 */
    @JsonProperty(SearchBizConstant.FIELD_CHUNK_TEXT)
    private String chunkText;

    /** 切片向量（与 article_idx 的 vec_dense 同一 Embedding 单模型） */
    @JsonProperty(SearchBizConstant.FIELD_CHUNK_VECTOR)
    private List<Float> chunkVector;

    /** 分区 code 字符串（RAG 检索可选过滤） */
    @JsonProperty(SearchBizConstant.FIELD_ZONE)
    private String zone;

    /** 可见性闸门值（固定 "PUBLIC"） */
    @JsonProperty(SearchBizConstant.FIELD_VISIBILITY)
    private String visibility;

    /** 文章发布时间 */
    @JsonProperty(SearchBizConstant.FIELD_PUBLISH_TIME)
    private LocalDateTime publishTime;

    /** 切片入库时间（重建排查用） */
    @JsonProperty(SearchBizConstant.FIELD_INGEST_TIME)
    private LocalDateTime ingestTime;
}
