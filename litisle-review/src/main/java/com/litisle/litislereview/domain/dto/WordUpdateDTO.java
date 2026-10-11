package com.litisle.litislereview.domain.dto;

import com.litisle.litislereview.enums.WordLevelEnum;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改敏感词入参（PUT /api/review/word）
 * <p>仅开放运营可变更字段：word/level/isBlacklist/status，其余字段 null 视为沿用原值（增量更新语义）；
 * scene 不允许变更（跨场景迁移需双场景 bump，一期不支持）；
 * id 之外的 updatedBy/version/createTime/updateTime 等服务端字段一律不接收，由服务端固化，防请求体越权改写</p>
 */
@Data
public class WordUpdateDTO {

    /** 敏感词行 id（定位唯一修改目标），必传 */
    @NotNull(message = "敏感词 id 不能为空")
    private Long id;

    /** 敏感词内容；非空时重走长度+字符校验与同场景查重；null 视为沿用原值 */
    @Size(max = 64, message = "敏感词长度不能超过 64 个字符")
    private String word;

    /** 级别（兼容三值：name HIGH/MID、code 1/2、desc 高危/中危）；null 视为沿用原值 */
    private WordLevelEnum level;

    /** 名单类型（兼容三值：name WHITELIST/BLACKLIST、code 0/1、desc 白名单/黑名单）；null 视为沿用原值 */
    private WordListTypeEnum isBlacklist;

    /** 启停状态（兼容三值：name DISABLED/ENABLED、code 0/1、desc 禁用/启用）；变更触发版本+1；null 视为沿用原值 */
    private WordStatusEnum status;
}
