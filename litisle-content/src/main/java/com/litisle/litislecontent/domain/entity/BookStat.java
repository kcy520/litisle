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
 * book_stat 书籍互动统计实体（批次十一 11.1）
 *
 * <p>作用：把书籍流的收藏/阅读人数/章节数从实时聚合中拆出，作为推荐流排序与列表展示的统一读源。
 * 书籍与统计行一对一，主键即 book.id。</p>
 *
 * <p>写入纪律：reader_count 只增不减，与 reading_history 裁剪完全解耦；
 * 计数一律原子自增，禁止先查后写。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("book_stat")
public class BookStat implements Serializable {

    /** 序列化版本号（实体跨进程传输兼容标识） */
    private static final long serialVersionUID = 1L;

    /**
     * 书籍ID
     * <p>主键，与 book.id 一对一；由业务写入方指定（非自增）</p>
     */
    @TableId(value = "book_id", type = IdType.INPUT)
    private Long bookId;

    /**
     * 收藏数
     * <p>来源 favorite（target_type=2）增量；取消收藏递减带下限保护</p>
     */
    @TableField("collect_count")
    private Long collectCount;

    /**
     * 阅读人数
     * <p>同一用户首次读该书时 +1，重复阅读不再加；只增不减，不受阅读历史裁剪影响</p>
     */
    @TableField("reader_count")
    private Long readerCount;

    /**
     * 章节数
     * <p>由 book_chapter 聚合同步（批次十一 11.2 切读后用于书籍卡片展示与排序）</p>
     */
    @TableField("chapter_count")
    private Long chapterCount;

    /**
     * 点赞数
     * <p>来源 user_like（target_type=2）增量（批次十一 11.8）；取消点赞递减带下限保护。
     * 书籍点赞只落本统计表，不进入 search 的 book_idx（该索引无点赞字段，详见 11.14 口径）</p>
     */
    @TableField("like_count")
    private Long likeCount;

    /**
     * 浏览量
     * <p>书籍浏览量当前无采集链路，字段保留，值为 0（后续接入书籍详情 PV 时复用）</p>
     */
    @TableField("view_count")
    private Long viewCount;

    /**
     * 统计最近更新时间
     * <p>仅计数变更刷新，供对账识别漂移行</p>
     */
    @TableField("stat_update_time")
    private LocalDateTime statUpdateTime;
}
