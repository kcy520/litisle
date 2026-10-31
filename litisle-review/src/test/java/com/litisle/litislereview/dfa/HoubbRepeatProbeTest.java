package com.litisle.litislereview.dfa;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * houbb ignoreRepeat 语义探针（批次3 ⑥ 归因用，结论落定后删除）
 */
class HoubbRepeatProbeTest {

    private static final List<String> DENY = List.of("谢谢", "好好学习", "杀人", "abc12");

    @Test
    void probe() {
        // 配置A：复刻旧生产六开关（ignoreRepeat=true）
        SensitiveWordBs on = SensitiveWordBs.newInstance()
                .wordDeny(() -> DENY).wordAllow(List::of)
                .ignoreCase(true).ignoreWidth(true).ignoreNumStyle(true)
                .ignoreChineseStyle(true).ignoreEnglishStyle(true).ignoreRepeat(true)
                .init();
        // 配置B：仅关 ignoreRepeat 作对照
        SensitiveWordBs off = SensitiveWordBs.newInstance()
                .wordDeny(() -> DENY).wordAllow(List::of)
                .ignoreCase(true).ignoreWidth(true).ignoreNumStyle(true)
                .ignoreChineseStyle(true).ignoreEnglishStyle(true).ignoreRepeat(false)
                .init();
        // 配置C：allow 白名单语义探针（deny=荣耀 allow=王者荣耀）
        SensitiveWordBs allowBs = SensitiveWordBs.newInstance()
                .wordDeny(() -> List.of("荣耀")).wordAllow(() -> List.of("王者荣耀"))
                .ignoreCase(true).ignoreWidth(true).ignoreNumStyle(true)
                .ignoreChineseStyle(true).ignoreEnglishStyle(true).ignoreRepeat(true)
                .init();
        List<String> texts = List.of(
                "谢", "谢谢", "谢谢谢", "谢谢谢谢", "感谢帮助",
                "好好学习", "好好学学习", "好好学学学习", "好好好好好学学学学学学习",
                "杀人", "我要殺人", "abc12", "ＡＢＣ１２", "天气不错");
        for (String t : texts) {
            System.out.println("[PROBE] text=" + t
                    + " | repeatON=" + on.findAll(t)
                    + " | repeatOFF=" + off.findAll(t));
        }
        List<String> allowTexts = List.of("王者荣耀很好玩", "这个荣耀真垃圾", "荣耀", "王者荣耀");
        for (String t : allowTexts) {
            System.out.println("[PROBE-ALLOW] text=" + t + " | result=" + allowBs.findAll(t));
        }
    }
}
