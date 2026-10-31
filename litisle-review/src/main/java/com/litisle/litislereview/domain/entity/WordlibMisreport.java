package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * wordlib_misreport 实体（词库误报上报记录表）
 *
 * <p>定位：词级误报明细落库，与场景级 Counter（wordlib_misreport_total{scene}）互补——
 * 指标管趋势、本表管明细；灰度转正裁决按 scene+word 聚合 report_count 算单词误报率，
 * 与 wordlib_change_audit（变更审计）打通「变更→误报→转正」闭环追溯</p>
 *
 * <p>去重口径：reporter_id+scene+word 唯一键，同上报人重复上报原子累加 report_count
 * 不新增行（防刷计数）；误报属运营数据非合规硬凭证，TTL 清理由 sweeper 按外置保留期执行</p>
 */
@Data
@Accessors(chain = true)
@TableName("wordlib_misreport")
public class WordlibMisreport implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 误报上报ID（自增主键）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 误报词（上报时校验须为该 scene 黑名单词，防瞎编词名刷计数）
     */
    @TableField("word")
    private String word;

    /**
     * 场景：ARTICLE/COMMENT/USERNAME/MESSAGE/BIO
     */
    @TableField("scene")
    private WordSceneEnum scene;

    /**
     * 上报时词级别快照：1-HIGH 2-MID（词级别后续变更不影响历史行裁决口径）
     */
    @TableField("level_snapshot")
    private WordLevelEnum levelSnapshot;

    /**
     * 误报说明（用户填写；应用层剥 CRLF + 超长截断 200）
     */
    @TableField("reason")
    private String reason;

    /**
     * 上报人ID
     */
    @TableField("reporter_id")
    private Long reporterId;

    /**
     * 该上报人对此词的累计上报次数（重复上报原子累加不新增行，防刷计数）
     */
    @TableField("report_count")
    private Integer reportCount;

    /**
     * 首次上报时间
     */
    @TableField("first_time")
    private LocalDateTime firstTime;

    /**
     * 最近上报时间（重复上报刷新；误报频度与 TTL 清理以此列计算）
     */
    @TableField("last_report_time")
    private LocalDateTime lastReportTime;
}
