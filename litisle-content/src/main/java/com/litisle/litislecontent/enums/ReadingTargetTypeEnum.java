package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import com.litisle.litislecontent.constant.ContentBizConstant;
import lombok.Getter;

import java.util.Arrays;

/**
 * 阅读历史「阅读对象类型」枚举：BOOK(1-书籍)/ARTICLE(2-独立文章)
 *
 * <p>语义区分（重点，避免与其他 bizType 混用）：
 * <ul>
 *   <li>审核侧 {@code ModerationConstant.BIZ_TYPE_*}：1-文章 2-评论，语义完全不同，禁止复用</li>
 *   <li>收藏侧 {@link FavoriteTargetTypeEnum}：1-文章 2-书籍，顺序与本枚举相反，禁止复用</li>
 *   <li>本枚举专用于 reading_history.biz_type：1-书籍 2-独立文章</li>
 * </ul>
 *
 * <p>对外契约：HTTP 请求 DTO / 响应 VO 仍用 Integer 接传（编码值与枚举 code 一致），
 * 由 Service 统一 {@link #resolve(Integer)} 做白名单校验，避免枚举反序列化歧义；
 * 实体字段用本枚举，MybatisEnumTypeHandler 按 @EnumValue 映射 tinyint。</p>
 */
@Getter
public enum ReadingTargetTypeEnum implements IntDictEnum {

    /** 书籍（历史行含 chapter_id，指向 book.id） */
    BOOK(ContentBizConstant.READING_BIZ_TYPE_BOOK, "书籍"),

    /** 独立文章（历史行 chapter_id 为 null，指向 article.id） */
    ARTICLE(ContentBizConstant.READING_BIZ_TYPE_ARTICLE, "独立文章");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    /**
     * 构造阅读对象类型枚举常量
     *
     * @param code 入库编码（1-书籍 2-独立文章）
     * @param desc 中文描述（JSON 序列化值）
     */
    ReadingTargetTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 白名单解析：请求侧 Integer code 转枚举
     *
     * @param code 请求入参的阅读对象类型编码（null-全部 1-书籍 2-独立文章）
     * @return 对应枚举；code 为空或不在白名单时返回 null（由 Service 抛参数错误）
     */
    public static ReadingTargetTypeEnum resolve(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
    }
}
