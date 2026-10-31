package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.Getter;

/**
 * 敏感词级别枚举（sensitive_word_library.level）
 * <p>1-HIGH 2-MID</p>
 * <p>code 为 int（入库 tinyint），接入 IntDictEnum 获得 name/code/desc 三值绑定</p>
 */
@Getter
public enum WordLevelEnum implements IntDictEnum {

    /** HIGH（高危） */
    HIGH(1, "高危"),

    /** MID（中危）灰度纪律 */
    MID(2, "中危");

//     ## 关键语义差异：MID 误报的「来源」和 HIGH 完全不同
// 这是理解 MID 误报逻辑最重要的一点：

// - HIGH 误报 = 受害者反馈 （强信号）：HIGH 同步拒发，用户内容真发不出去，用户有真实的切肤之痛才会来上报，误报证据可信度高
// - MID 误报 = 观察者反馈 （弱信号）：MID 只标记不拦截， 普通用户根本感知不到 MID 词命中过 ——没有「被冤枉」的体验，不会主动上报
// 所以 MID 误报上报的现实触发者实际是：

// 1. 审核员人审时 ：异步机审任务快照 hitWords 里带着 MID 命中词，审核员判断某 MID 词是误伤（如谐音、正常搭配），用同一接口上报
// 2. 运营主动验证 ：在词库列表看到某 MID 词命中量高，抽查内容后确认误伤，补充上报
// 你在之前选中那段话里写的「审核员/用户」，代码层面都走同一入口（`@SaCheckLogin` 不分角色），但 用户侧对 MID 词实际上没有触发场景 ——这是个值得记住的口径细节。

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordLevelEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}