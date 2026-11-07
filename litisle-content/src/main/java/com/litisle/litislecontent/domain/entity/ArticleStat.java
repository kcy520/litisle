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
 * article_stat 文章互动统计实体（批次十一 11.1）
 *
 * <p>作用：把文章的收藏/点赞/评论/浏览计数从 article 主表拆出，作为推荐流与热度榜的统一读源，
 * 避免每次刷流都对 article 大表做聚合查询。文章与统计行一对一，主键即 article.id。</p>
 *
 * <p>写入纪律：计数只能通过 Mapper 的单条 SQL 原子自增/自减，禁止「查出来改完再写回」；
 * 统计行不存在时由 upsert 补建，读侧缺行按 0 处理。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("article_stat")
public class ArticleStat implements Serializable {

    /** 序列化版本号（实体跨进程传输兼容标识） */
    private static final long serialVersionUID = 1L;

    /**
     * 文章ID
     * <p>主键，与 article.id 一对一；由业务写入方指定（非自增）</p>
     */
    @TableId(value = "article_id", type = IdType.INPUT)
    private Long articleId;

    /**
     * 收藏数
     * <p>来源 favorite（target_type=1）增量；取消收藏递减带下限保护，不会小于 0</p>
     */
    @TableField("collect_count")
    private Long collectCount;

    /**
     * 点赞数
     * <p>批次十一 11.1 只建字段与写入入口；11.8 点赞接口上线后开始产生增量，此前恒为 0</p>
     */
    @TableField("like_count")
    private Long likeCount;

    /**
     * 评论数
     * <p>由 social 评论侧回写；当前评论模块未落地，恒为 0</p>
     */
    @TableField("comment_count")
    private Long commentCount;

    /**
     * 浏览量
     * <p>ViewCountFlushJob 落库 article.view_cnt 后同步绝对值；只增不减</p>
     */
    @TableField("view_count")
    private Long viewCount;

    /**
     * 统计最近更新时间
     * <p>仅计数变更刷新，供对账与后续增量灌榜识别变更行</p>
     */
    @TableField("stat_update_time")
    private LocalDateTime statUpdateTime;
}
