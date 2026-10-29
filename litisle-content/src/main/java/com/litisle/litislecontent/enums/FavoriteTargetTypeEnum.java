package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

import java.util.Arrays;

/**
 * 收藏目标类型枚举：ARTICLE(1)/BOOK(2)
 * <p>阶段16：一期 content 临时承载单层收藏；请求 DTO 用 Integer 接参，Service 统一 resolve，
 * 避免枚举反序列化歧义（非法 code 不进枚举，由 Service 抛参数错误）</p>
 */
@Getter
public enum FavoriteTargetTypeEnum implements IntDictEnum {

    /** 文章 */
    ARTICLE(1, "文章"),

    /** 书籍 */
    BOOK(2, "书籍");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    FavoriteTargetTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 白名单解析：请求侧 Integer code 转枚举
     *
     * @param code 请求入参的目标类型编码（1-文章 2-书籍）
     * @return 对应枚举；code 为空或不在白名单时返回 null（由 Service 抛参数错误）
     */
    public static FavoriteTargetTypeEnum resolve(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code == code).findFirst().orElse(null);
    }
}
