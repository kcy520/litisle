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
 * content_stat_window 内容互动统计窗口实体（批次十一 11.3）
 *
 * <p>作用：把收藏/阅读的时间窗计数（默认 30 天、90 天）预计算落表，推荐流排序翻页时只做
 * 按 ID 集合的批量读取，不在查询期对 favorite / reading_history 做任何聚合。</p>
 *
 * <p>结构：同一目标按「目标类型 + 目标ID + 窗口天数」唯一，一行对应一个窗口；
 * 窗口天数来自配置，调整天数不需要改表（只是多一组行）。</p>
 *
 * <p>更新方式：ContentStatWindowJob 每日全量 upsert（幂等）；该表不承载累计值，
 * 累计值仍在 article_stat / book_stat，窗口行缺失时读侧回退累计值兜底。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("content_stat_window")
public class ContentStatWindow implements Serializable {

    /** 序列化版本号（实体跨进程传输兼容标识） */
    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     * <p>仅作物理主键，业务唯一性由 uk_target_window(target_type, target_id, window_days) 保证</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 目标类型：1-文章 2-书籍
     * <p>与 favorite.target_type 编码一致（ContentBizConstant.STAT_WINDOW_TARGET_*）</p>
     */
    @TableField("target_type")
    private Integer targetType;

    /**
     * 目标ID（文章ID或书籍ID）
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 统计窗口天数（默认 30/90，取自 litisle.content.stat.window 配置）
     */
    @TableField("window_days")
    private Integer windowDays;

    /**
     * 窗口内收藏数
     * <p>来源 favorite，按 create_time 落在窗口内的行计数；每日重算覆盖</p>
     */
    @TableField("collect_count")
    private Long collectCount;

    /**
     * 窗口内阅读人数
     * <p>来源 reading_history，按 update_time 落在窗口内去重用户数；每日重算覆盖。
     * 受阅读历史 90 天裁剪约束：窗口天数不超过裁剪保留天数时准确，超过则偏小</p>
     */
    @TableField("read_count")
    private Long readCount;

    /**
     * 统计最近更新时间
     * <p>每日重算刷新，供人工排查窗口任务是否正常执行</p>
     */
    @TableField("stat_update_time")
    private LocalDateTime statUpdateTime;
}
