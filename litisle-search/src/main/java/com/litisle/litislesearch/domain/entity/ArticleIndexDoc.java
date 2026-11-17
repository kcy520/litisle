package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章索引文档（article_idx 的 ES 文档映射实体，非 MySQL 实体）。
 *
 * 【说明】search 无业务数据表，本类只是 content 文章在 ES 中的检索投影；
 * 字段与 EsIndexInitializer#articleMapping 一一对应，写入时按 articleId 幂等 upsert。
 * 【字段口径】visibility 固定写 "PUBLIC"（写入侧闸门）；zone 存分区 code 字符串，category 存分类名；
 * 向量 vecDense 在 Embedding 提供方接入前不写（字段缺省，不影响其余检索字段）。
 * 【命名说明】Java 字段用驼峰，@JsonProperty 引用常量绑定 ES 下划线字段名，避免字段名字符串散落。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleIndexDoc {

    /** 文档主键：articleId 字符串（与 mapping keyword 类型一致，幂等 upsert 依据） */
    @JsonProperty(SearchFieldConstant.FIELD_ID)
    private String id;

    /** 标题（multi_match 权重 ^4） */
    @JsonProperty(SearchFieldConstant.FIELD_TITLE)
    private String title;

    /** 标签名列表（keyword 数组，权重 ^3，兼作标签重合度计算） */
    @JsonProperty(SearchFieldConstant.FIELD_TAGS)
    private List<String> tags;

    /** 摘要（权重 ^2） */
    @JsonProperty(SearchFieldConstant.FIELD_SUMMARY)
    private String summary;

    /** 正文纯文本（HTML 去标签后的全文检索字段，返回高亮片段） */
    @JsonProperty(SearchFieldConstant.FIELD_CONTENT)
    private String content;

    /** 可见性闸门值（固定 "PUBLIC"，查询侧另有 filter 双保险） */
    @JsonProperty(SearchFieldConstant.FIELD_VISIBILITY)
    private String visibility;

    /** 作者 ID（keyword，作者维度筛选/聚合） */
    @JsonProperty(SearchFieldConstant.FIELD_AUTHOR_ID)
    private String authorId;

    /** 作者昵称（ik 分词 + completion 联想源，可为空） */
    @JsonProperty(SearchFieldConstant.FIELD_AUTHOR_NAME)
    private String authorName;

    /** 分区 code 字符串（keyword 筛选维度） */
    @JsonProperty(SearchFieldConstant.FIELD_ZONE)
    private String zone;

    /** 分类名（keyword 筛选维度） */
    @JsonProperty(SearchFieldConstant.FIELD_CATEGORY)
    private String category;

    /** 点赞数基数（互动事件增量维护） */
    @JsonProperty(SearchFieldConstant.FIELD_LIKE_CNT)
    private Long likeCnt;

    /** 评论数基数（互动事件增量维护） */
    @JsonProperty(SearchFieldConstant.FIELD_COMMENT_CNT)
    private Long commentCnt;

    /** 浏览数基数（浏览量 flush 事件覆盖维护） */
    @JsonProperty(SearchFieldConstant.FIELD_VIEW_CNT)
    private Long viewCnt;

    /** 互动热度（发布时初始 0，批次三刷写任务按 A5 公式重算） */
    @JsonProperty(SearchFieldConstant.FIELD_HEAT)
    private Double heat;

    /** 文章向量（1024 维 cosine，KNN 相似推荐；Embedding 未接入时缺省） */
    @JsonProperty(SearchFieldConstant.FIELD_VEC_DENSE)
    private List<Float> vecDense;

    /** 更新时间（NEWEST 排序，同时作为发布时间语义） */
    @JsonProperty(SearchFieldConstant.FIELD_UPDATE_TIME)
    private LocalDateTime updateTime;

    /** ai_labels 维度标签槽（板块八/search 板块二 2.2：key=注册表维度名，value=词表值或语义短语；打标失败留空不阻断） */
    @JsonProperty(SearchFieldConstant.FIELD_AI_LABELS)
    private java.util.Map<String, String> aiLabels;

    /** 正文字数（索引期按正文纯文本纯计算字数，零 AI 成本；word_count range 过滤维度绑定字段） */
    @JsonProperty(SearchFieldConstant.FIELD_WORD_COUNT)
    private Long wordCount;
}
