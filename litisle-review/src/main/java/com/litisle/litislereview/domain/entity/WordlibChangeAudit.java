package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.WordlibOpTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * wordlib_change_audit 实体（词库变更审计表，批次1 ②：词库治理基建）
 * <p>红线：与词表变更同一事务插入（审计不同事务=不可信）——收口位置
 * WordlibVersionService.bumpAndBroadcast，随版本推进一起提交/回滚</p>
 * <p>审计口径：单条变更逐词 1 行（ADD/UPDATE/DELETE）；批量导入聚合 1 行
 * （BATCH_IMPORT + word_count，词清单本身在备份文件里不重复落库）</p>
 * <p>追溯用途：误报灰度转正（updateWord 升级 HIGH）等词级治理动作经本表闭环追溯</p>
 */
@Data
@Accessors(chain = true)
@TableName("wordlib_change_audit")
public class WordlibChangeAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 审计ID（自增主键）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 场景：ARTICLE/COMMENT/USERNAME/MESSAGE/BIO
     */
    @TableField("scene")
    private WordSceneEnum scene;

    /**
     * 操作类型：ADD/UPDATE/DELETE/BATCH_IMPORT
     */
    @TableField("op_type")
    private WordlibOpTypeEnum opType;

    /**
     * 变更词（单条变更逐词审计；批量导入为空——词清单在备份文件里不重复落库）
     */
    @TableField("word")
    private String word;

    /**
     * 变更摘要（如 level: MID→HIGH；批量导入为空）
     */
    @TableField("detail")
    private String detail;

    /**
     * 批量导入净新增词数（仅 BATCH_IMPORT 有值）
     */
    @TableField("word_count")
    private Integer wordCount;

    /**
     * 本次变更对应词库冷备文件名（{scene}-v{version}.csv.gz，与备份 SPI 落盘文件一一对应）
     */
    @TableField("backup_file")
    private String backupFile;

    /**
     * 变更后 scene 词库版本（= sensitive_word_version.version，本事务内推进值）
     */
    @TableField("version")
    private Long version;

    /**
     * 操作人ID（审计；系统触发为空）
     */
    @TableField("operator_id")
    private Long operatorId;

    /**
     * 审计时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}
