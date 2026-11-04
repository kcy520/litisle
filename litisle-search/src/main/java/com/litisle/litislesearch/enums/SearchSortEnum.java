package com.litisle.litislesearch.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 检索排序枚举（架构文档 §2.2）：
 * RELATED("related") → 相关度排序（默认，relevance + function_score 热度加权）
 * NEWEST("newest")   → updateTime 降序
 * HEAT("heat")       → heat 降序（互动热度公式见架构文档 §四）
 *
 * code 与 REST 参数 /search?sort= 取值一致（小写），
 * 实现 DictEnum：接入 common-core 三值兼容绑定（name/code/desc 均可作为请求参数）
 */
@Getter
public enum SearchSortEnum implements DictEnum {

    /** 相关度排序（默认）：multi_match 相关性 + function_score(heat) 加权 */
    RELATED("related", "相关度"),

    /** 最新排序：updateTime 降序 */
    NEWEST("newest", "最新"),

    /** 最热排序：heat 降序 */
    HEAT("heat", "最热");

    /** REST 参数值（如 ?sort=newest） */
    @JsonValue
    private final String code;

    /** 前端展示用中文描述 */
    private final String desc;

    SearchSortEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
