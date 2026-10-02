package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * sensitive_word_library 实体（敏感词库表，DFA 热更新数据源）
 * <p>物理删除：唯一键与逻辑删除互斥，配置类小表可全量重建，删除即物理删、updated_by 留审计痕</p>
 * <p>同时支持黑/白名单与多场景：按 scene 过滤加载（评论加载白名单 / 用户名不加载白名单）</p>
 * <p>MyBatis-Plus：@TableName("sensitive_word_library") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("sensitive_word_library")
public class SensitiveWordLibrary implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 词ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 敏感词（导入时长度上限 + 字符校验，DFA 构建前拦截）
     */
    @Size(max = 64, message = "敏感词长度不能超过 64 个字符")
    @TableField("word")
    private String word;

    /**
     * 场景：ARTICLE/COMMENT/USERNAME/MESSAGE
     */
    @TableField("scene")
    private WordSceneEnum scene;

    /**
     * 级别：1-HIGH 2-MID
     */
    @TableField("level")
    private WordLevelEnum level;

    /**
     * 是否黑名单：0-白名单 1-黑名单
     */
    @TableField("is_blacklist")
    private WordListTypeEnum isBlacklist;

    /**
     * 状态：0-禁用 1-启用
     */
    @TableField("status")
    private WordStatusEnum status;

    /**
     * 修改人ID（审计）
     */
    @TableField("updated_by")
    private Long updatedBy;

    /**
     * 该词最后变更时的 scene 版本快照（只读参考，非版本源；版本源见 sensitive_word_version）
     */
    @TableField("version")
    private Long version;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}