package com.litisle.litislereview.domain.vo;

import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 敏感词库列表出参（GET /api/review/word/list）
 * <p>不回吐 updatedBy（操作人ID）与 version（行级版本快照）等服务端审计字段，
 * 仅保留运营可视信息；枚举字段按 @JsonValue 序列化为中文 desc</p>
 */
@Data
@Accessors(chain = true)
public class SensitiveWordVO {

    /** 敏感词行 id */
    private Long id;

    /** 敏感词内容 */
    private String word;

    /** 场景（序列化为 desc：文章/评论/用户名/私信） */
    private WordSceneEnum scene;

    /** 级别（序列化为 desc：高危/中危） */
    private WordLevelEnum level;

    /** 名单类型（序列化为 desc：白名单/黑名单） */
    private WordListTypeEnum isBlacklist;

    /** 启停状态（序列化为 desc：禁用/启用） */
    private WordStatusEnum status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
