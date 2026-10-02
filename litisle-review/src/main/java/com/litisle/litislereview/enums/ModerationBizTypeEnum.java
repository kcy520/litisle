package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 审核业务类型枚举（review_task.biz_type / moderation_record.biz_type /
 * punish_log.biz_type / appeal.biz_type 共用）
 * <p>1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片（biz_type=6 时 biz_id = file_meta.id）</p>
 * <p>注意：与 report.target_type 枚举错位（私信 5↔4、图片 6↔5），映射见 Review-Plan §4.5.2，
 * 禁按 biz_type==target_type 恒等实现，否则反查举报零行卡死</p>
 * <p>code 为 int 型，不接入 DictEnum（见 DictEnum 契约说明）</p>
 */
@Getter
public enum ModerationBizTypeEnum {

    /** 文章 */
    ARTICLE(1, "文章"),

    /** 评论 */
    COMMENT(2, "评论"),

    /** 用户名（注册同步 DFA 拒绝时用户尚不存在，约定 biz_id=0） */
    USERNAME(3, "用户名"),

    /** 作家申请 */
    AUTHOR_APPLY(4, "作家申请"),

    /** 私信 */
    MESSAGE(5, "私信"),

    /** 文件/图片（biz_id = file_meta.id，content_hash = 文件 sha256） */
    FILE(6, "文件/图片");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ModerationBizTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}