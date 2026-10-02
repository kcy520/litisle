package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 审核场景枚举（review_task.scene / moderation_record.scene）
 * <p>发布场景与 *_RE_REVIEW 举报/复审场景分列，防举报转人工 upsert 撞 uk_biz_scene
 * 重置已终态任务（Review-Plan §4.5.3）</p>
 * <p>最长场景名 ARTICLE_IMG_RE_REVIEW 恰 20 字符，DB 列留余量取 varchar(32)</p>
 * <p>code 为 String，接入 DictEnum 获得 name/code/desc 三值绑定</p>
 */
@Getter
public enum ReviewSceneEnum implements DictEnum {

    /** 文章-精选发布 */
    ARTICLE_FEATURED("ARTICLE_FEATURED", "文章精选"),

    /** 文章-社区发布 */
    ARTICLE_COMMUNITY("ARTICLE_COMMUNITY", "文章社区"),

    /** 文章-举报/复审 */
    ARTICLE_RE_REVIEW("ARTICLE_RE_REVIEW", "文章复审"),

    /** 评论-发布 */
    COMMENT("COMMENT", "评论"),

    /** 评论-举报/复审 */
    COMMENT_RE_REVIEW("COMMENT_RE_REVIEW", "评论复审"),

    /** 用户名（注册同步 DFA，不建任务，仅落 moderation_record） */
    USERNAME("USERNAME", "用户名"),

    /** 私信-先发后审 */
    MESSAGE("MESSAGE", "私信"),

    /** 私信-举报/复审 */
    MESSAGE_RE_REVIEW("MESSAGE_RE_REVIEW", "私信复审"),

    /** 图片（单图审核，biz_id = file_meta.id） */
    IMAGE("IMAGE", "图片"),

    /** 文章配图-举报/复审（文章级全部配图 sha256 聚合指纹复审） */
    ARTICLE_IMG_RE_REVIEW("ARTICLE_IMG_RE_REVIEW", "文章配图复审");

    /** 入库值（varchar(32)） */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    ReviewSceneEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}