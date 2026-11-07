package com.litisle.litislecontent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * book_read_quality 书籍粗完读率代理统计实体（批次十一 11.13）
 *
 * <p>作用：在没有前端进度埋点的前提下，用「最后一次阅读的章节号」与「该书当前最大可见章节号」
 * 比较，近似判断用户是否追上最新进度。本表只读展示，不参与推荐排序。</p>
 *
 * <p>三个已知误差（产品口径必须知情）：</p>
 * <ul>
 *   <li>打开即算阅读：用户点开最后一章后立刻离开，也会被判为完读（假阳性）；</li>
 *   <li>书更新后新章出现：原本追平的用户会变回未完成（假阴性，属于「进度被刷新」的正常语义）；</li>
 *   <li>只读最后一章：从未从头阅读、直接翻阅最后一章的用户也会被判完读（假阳性）。</li>
 * </ul>
 * <p>额外边界（实现层）：</p>
 * <ul>
 *   <li>阅读历史只保留最后一次阅读位置：用户追平后又回看中间章节，会被重新判为未完成；</li>
 *   <li>最大章节号只统计「已发布 + 未删 + 公开可见」章节；仅关注者可见（visibility=3）的章节
 *       需要社交关系才能逐用户判定，纯 SQL 不做个性化判定，因此关注者可能被提前判完读。</li>
 * </ul>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("book_read_quality")
public class BookReadQuality implements Serializable {

    /** 序列化版本号（实体跨进程传输兼容标识） */
    private static final long serialVersionUID = 1L;

    /** 书籍ID（主键，与 book.id 一对一，由统计任务写入） */
    @TableId(value = "book_id", type = IdType.INPUT)
    private Long bookId;

    /** 追到最大可见章节号的读者人数（分子；与 eligibleReaderCount 同一次全量重算产出） */
    @TableField("finish_reader_count")
    private Long finishReaderCount;

    /**
     * 有可见章节阅读历史的读者人数（分母）
     * <p>与分子同源计算：只统计最后一次阅读章节仍可解析到公开可见章节的用户，
     * 避免用累计只增不减的 book_stat.reader_count 当分母导致比率被稀释</p>
     */
    @TableField("eligible_reader_count")
    private Long eligibleReaderCount;

    /** 本轮全量重算时间（供排查统计滞后） */
    @TableField("stat_update_time")
    private LocalDateTime statUpdateTime;
}
