package com.litisle.litisleapi.dfa;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

/**
 * 字符归一化器（批次2 ③：AC 引擎的统一归一口径，替代 houbb 六开关中的无损宽化部分）
 *
 * <p>【归一化双向分治——本类只做"无损宽化"】每个字符 1:1 或 1:N 映射到标准形，
 * 不丢失信息、不折叠重复：</p>
 * <ul>
 *   <li>大小写互换（A→a）——对应 houbb ignoreCase</li>
 *   <li>全角半角互换（Ａ→A、１→1、！→!）——对应 houbb ignoreWidth</li>
 *   <li>数字写法互换（⓿→0、①→1、⑩→10，10 以上数字为 1:N 展开）——对应 houbb ignoreNumStyle</li>
 *   <li>英文字母序号互换（Ⓐ→a、ⓕ→f）——对应 houbb ignoreEnglishStyle</li>
 *   <li>中文繁简互换（殺→杀）——对应 houbb ignoreChineseStyle，走 opencc4j 同源引擎
 *       （houbb 内部亦委托 opencc4j，保证批次3 影子比对繁简口径完全一致）</li>
 * </ul>
 *
 * <p>【红线：重复字符折叠（N:1 有损归一）禁在本类实现】文本侧若把「谢谢」折叠成「谢」，
 * 词库单字「谢」会误命中正常叠词致全量误报，且 1:N 展开（⑩→10）后位置错乱；
 * 重复变体对抗改在 {@link AcTextScanEngine} 构建期把词展开为变体 pattern 进 trie，
 * 文本侧永不折叠。任何改动必须保持：文本与词库两侧调用同一 normalize，口径分叉=变形词漏审</p>
 *
 * <p>线程安全：纯函数无状态（opencc4j 转换器内部线程安全），可并发调用</p>
 */
public final class CharNormalizer {

    private CharNormalizer() {
        // 工具类禁实例化
    }

//    术语	        意思	                                举例
//    无损宽化	一个字符变成等量的或更多的标准字符，信息不丢	Ａ→A（1:1）、⑩→"10"（1:2）
//    有损折叠	多个字符压缩成更少的字符，信息丢失	谢谢→谢（2:1）、⓪⓪→0（2:1）
//      文本侧像"翻译官"——只把变形字符原原本本翻译成标准写法，不删不改不压缩；真正的对抗逻辑在词库构建期，把每种变体都提前塞进 trie 里等着。

//    这个 CharNormalizer 是我们 AC 敏感词引擎的统一归一口径，负责把用户输入文本里的各种 Unicode 变形字符——全角、大小写、字母序号、数字变体、繁体中文——无损地展开成标准 ASCII 形态，让后面的 AC 自动机只需要维护一份标准词库，不会被花式写法绕过。
//    只做无损宽化（1:1 / 1:N），永不有损折叠（N:1）。文本侧不删字符、不压重复，重复变体对抗放词库构建期。


    /**
     * 文本归一化（同步闸门扫描与词库 pattern 注册共用的唯一入口）
     *
     * <p>处理顺序：繁→简（词级转换需在字符级变换前做，opencc4j 词典按原始字符匹配）
     * → 逐字符：全角半角 → 字母序号 → 数字写法 → 小写折叠</p>
     *
     * @param text 原始文本（null 安全）
     * @return 归一化文本；无任何变体字符时与原文一致（宽化归一不删字符）
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Step1 繁→简：opencc4j 词级转换（短语歧义由词典处理，如「皇后」「后面」）；
        // 快速通道：不含 CJK 统一表意文字的文本跳过词典查询（纯英文/数字高频路径省开销）
        String simplified = containsCjk(text) ? ZhConverterUtil.toSimple(text) : text;
        // Step2 逐字符宽化归一（全角/字母序号/数字写法/小写）
        StringBuilder sb = new StringBuilder(simplified.length());
        for (int i = 0; i < simplified.length(); i++) {
            appendNormalized(sb, simplified.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 单字符归一化输出（每字符产出 1..N 个归一化字符，只增不删）
     *
     * 作用是把各种变形字符统一成标准 ASCII 字母/数字，让后面的 AC 自动机只匹配“标准形态”，不会被花式写法绕过
     */
    private static void appendNormalized(StringBuilder out, char ch) {
        char c = ch;
        // Step2.1 全角→半角：全角 ASCII 区（！-～）按 Unicode 偏移折半角；全角空格→半角空格
        if (c >= 0xFF01 && c <= 0xFF5E) {
            c = (char) (c - 0xFEE0);
        } else if (c == 0x3000) {
            out.append(' ');
            return;
        }
        // Step2.2 字母序号：带圈/负圈字母 Ⓐ-Ⓩ(U+24B6-24CF)、ⓐ-ⓩ(U+24D0-24E9) 折小写字母
        if (c >= 0x24B6 && c <= 0x24E9) {
            out.append((char) ('a' + (c - 0x24B6) % 26));
            return;
        }
        // Step2.3 数字写法：圆圈数字/括号数字/带点数字折阿拉伯数字（≥10 时 1:N 展开为多位）
        if (appendDigitVariant(out, c)) {
            return;
        }
        // Step2.4 小写折叠（A→a；非字母原样输出）
        out.append(Character.toLowerCase(c));
    }

    /**
     * 数字写法变体判定与展开：命中则向 out 追加对应阿拉伯数字（1:N，如 ⑩→"10"）
     *
     *这段代码是 appendNormalized 的“数字分支”专用方法，专门负责把 Unicode 里各种花式数字字符 统一展开成 标准阿拉伯数字字符串。
     * @return true=已按数字变体输出；false=非数字变体字符
     */
    private static boolean appendDigitVariant(StringBuilder out, char c) {
        // 圆圈数字 ①-⑳（U+2460-2473 → 1-20）
        if (c >= 0x2460 && c <= 0x2473) {
            appendDecimal(out, c - 0x2460 + 1);
            return true;
        }
        // 括号数字 ⑴-⒇（U+2474-2487 → 1-20）
        if (c >= 0x2474 && c <= 0x2487) {
            appendDecimal(out, c - 0x2474 + 1);
            return true;
        }
        // 带点数字 ⒈-⒛（U+2488-249B → 1-20，字形自带小数点语义忽略仅取数值）
        if (c >= 0x2488 && c <= 0x249B) {
            appendDecimal(out, c - 0x2488 + 1);
            return true;
        }
        // 圆圈 ⓪（U+24EA → 0）与负圈 ⓿（U+24FF → 0）
        if (c == 0x24EA || c == 0x24FF) {
            out.append('0');
            return true;
        }
        // 负圈数字 ⓫-⓴（U+24EB-24F4 → 11-20）
        if (c >= 0x24EB && c <= 0x24F4) {
            appendDecimal(out, c - 0x24EB + 11);
            return true;
        }
        // 带圈数字 ㉑-㉚（U+24F5-24FE → 21-30）
        if (c >= 0x24F5 && c <= 0x24FE) {
            appendDecimal(out, c - 0x24F5 + 21);
            return true;
        }
        // 中文括号数字 ㈠-㈩（U+3220-3229 → 1-10）
        if (c >= 0x3220 && c <= 0x3229) {
            appendDecimal(out, c - 0x3220 + 1);
            return true;
        }
        return false;
    }


    /**
     * 追加整数十进制表示（1:N 展开：⑩→"10"）
     *
     * appendDecimal 是数字变体归一化的语义落点——它把"数字的值"安全、可扩展、零分配地写入结果缓冲区，保证 1:N 展开不丢信息。
     */
    private static void appendDecimal(StringBuilder out, int value) {
        out.append(value);
    }


    /**
     * 是否含 CJK 统一表意文字（含扩展 A 区）：决定是否走 opencc4j 词典转换
     *
     * containsCjk 是一个低成本 CJK 检测器，作为 opencc4j 繁简转换的前置短路开关——有汉字才走词典，
     * 没有就直接跳过，保证高频英文/数字/符号路径的归一化开销最小化。
     */
    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
                return true;
            }
        }
        return false;
    }
}
