package com.litisle.litislereview.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 词库治理建议标记枚举（词库列表出参专用，§5.4 治理建议清单）
 *
 * <p>⚠️ 红线：标记只是证据展示、禁触发任何自动动作——转正/降级一律运营手动确认
 * （复用既有 updateWord），本枚举纯展示层、不入库（故不标 @EnumValue）</p>
 *
 * <p>三类标记口径（标记仅对黑名单词计算，白名单/禁用词无治理语义）：</p>
 * <ul>
 *   <li>建议降级：HIGH 且误报上报次数 > 0——以误报绝对量为主（同步闸门拦截不进 hit_stat
 *       分母，比率虚高仅作参考证据列展示）</li>
 *   <li>转正确认：HIGH 且命中样本 ≥ min-hit-sample 且误报率 &lt; gray-misreport-rate——
 *       观察合格，MID→HIGH 转正决策被数据背书</li>
 *   <li>建议转正：MID 且命中样本 ≥ min-hit-sample——异步命中观察量达标，进入转正裁决视野</li>
 * </ul>
 *
 * <p>已知局限（清单口径）：命中观察量仅统计 review 进程内异步机审（同步闸门 HIGH 拦截发生在
 * 宿主本地不建任务），HIGH 词分母缺失——降级建议以误报绝对量为主、比率仅参考</p>
 */
@Getter
public enum WordSuggestionMarkEnum implements DictEnum {

    /** 建议降级：HIGH + 误报上报次数 > 0（误报绝对量为主，比率虚高仅参考） */
    SUGGEST_DEMOTE("SUGGEST_DEMOTE", "建议降级"),

    /** 转正确认：HIGH + 命中样本 ≥ min-hit-sample 且误报率 < gray-misreport-rate */
    PROMOTE_CONFIRM("PROMOTE_CONFIRM", "转正确认"),

    /** 建议转正：MID + 命中样本 ≥ min-hit-sample（异步命中观察量达标） */
    SUGGEST_PROMOTE("SUGGEST_PROMOTE", "建议转正");

    /** 标识码（仅接口出参用，不入库——无 @EnumValue） */
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordSuggestionMarkEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
