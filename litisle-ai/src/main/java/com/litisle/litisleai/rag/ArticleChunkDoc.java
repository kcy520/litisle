package com.litisle.litisleai.rag;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * article_chunks 向量索引文档模型（板块四 4.1：AI 自建自管索引，P9 索引名隔离）
 *
 * <p>【字段口径】chunkId = articleId_chunkIndex（幂等键，重复写入即覆盖）；
 * contentVector 为 bge-m3 1024 维向量（dense_vector mapping）；
 * contentHash 承载 Q75 引用版本比对（检索命中后与 content 回查值比对，不一致标注过期）；
 * 字段对齐 ContentDTO 契约（bizId/ownerId/status/visibility/contentHash），无 authorName/updateTime。</p>
 *
 * @author litisle
 */
@Data
public class ArticleChunkDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 切片唯一 ID（articleId_chunkIndex，ES _id，幂等覆盖写） */
    private String chunkId;

    /** 文章 ID（按篇删除与引用溯源依据） */
    private Long articleId;

    /** 切片序号（同篇内 0 起） */
    private Integer chunkIndex;

    /** 文章标题（引用卡片展示） */
    private String title;

    /** 作者 ID */
    private Long authorId;

    /** 切片正文（清洗后文本） */
    private String chunkText;

    /** 切片向量（bge-m3 1024 维；dense_vector） */
    private List<Float> contentVector;

    /** 文章状态（摄入门控后恒为已发布 2；引用回查比对依据之一） */
    private Integer status;

    /** 可见性（摄入门控后恒为公开 1） */
    private Integer visibility;

    /** 文章内容哈希（Q75 引用版本比对依据：与 content 回查值不一致标注过期） */
    private String contentHash;

    /** 摄入时间 */
    private LocalDateTime ingestTime;
}
